package eve.hq.data

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintPageCache
import eve.hq.core.blueprint.CachedBlueprintPage
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.data.esi.EveBlueprintSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EveBlueprintSourceTest {
    private val alice = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

    @Test
    fun `an original and a copy keep their efficiencies, runs, names, location and max runs`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterBlueprints(123).willReturn(
                """[{"item_id":1001,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
                    "material_efficiency":10,"time_efficiency":20,"quantity":-1,"runs":-1},
                    {"item_id":1002,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
                    "material_efficiency":2,"time_efficiency":4,"quantity":-2,"runs":4}]""",
            )
            universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val blueprints = EveBlueprintSource(esi.client, esi.baseUrl, staticBlueprints = staticBlueprints()).load(alice)

        val rifter = AssetType(681, "Rifter Blueprint")
        val jita = AssetLocation(60003760, "Jita IV - Moon 4")
        assertThat(blueprints).containsExactly(
            OwnedBlueprint(1001, rifter, BlueprintKind.Original, 10, 20, remainingRuns = null, maxRuns = 300, location = jita),
            OwnedBlueprint(1002, rifter, BlueprintKind.Copy, 2, 4, remainingRuns = 4, maxRuns = 300, location = jita),
        )
    }

    @Test
    fun `every page of a paginated blueprint list is read`() = runBlocking<Unit> {
        fun page(itemId: Long) = """[{"item_id":$itemId,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
            "material_efficiency":0,"time_efficiency":0,"quantity":-1,"runs":-1}]"""
        val esi = EsiMock {
            characterBlueprints(123).page(1).willReturn(page(1001), headers = mapOf("X-Pages" to "2"))
            characterBlueprints(123).page(2).willReturn(page(1002), headers = mapOf("X-Pages" to "2"))
            universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val blueprints = EveBlueprintSource(esi.client, esi.baseUrl).load(alice)

        assertThat(blueprints.map(OwnedBlueprint::itemId)).containsExactly(1001L, 1002L)
        esi.characterBlueprints(123).assertPagesRequested(1, 2)
    }

    @Test
    fun `a forbidden response asks for re-authorization`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterBlueprints(123).willReturn("""{"error":"missing scope"}""", HttpStatusCode.Forbidden)
        }

        val failure = assertFailsWith<LoginRequired> { EveBlueprintSource(esi.client, esi.baseUrl).load(alice) }

        assertThat(failure.message).contains("Add character").contains("blueprint access")
    }

    @Test
    fun `any other failed response reports the endpoint and status`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterBlueprints(123).willReturn("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
        }

        val failure = assertFailsWith<Exception> { EveBlueprintSource(esi.client, esi.baseUrl).load(alice) }

        assertThat(failure.message).contains("blueprints").contains("500")
    }

    private fun staticBlueprints(): StaticBlueprintRepository {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        Migrations.migrate(dataSource)
        JDBEngine.builder(IStaticTypesTestSchema::class.java).dataSource(dataSource).build().resetDB { db ->
            db.categories().id(9).name("Blueprint")
            db.groups().id(105).category_id(9).name("Frigate Blueprint")
            db.types().id(681).group_id(105).name("Rifter Blueprint").published(true)
            db.blueprints().type_id(681).max_production_limit(300)
        }
        return StaticBlueprintRepository(dataSource)
    }

    @Test
    fun `fetched pages are saved with their validators and expiry`() = runBlocking<Unit> {
        val cache = MemoryBlueprintPageCache()
        val esi = EsiMock {
            characterBlueprints(123).willReturn(
                "[]",
                headers = mapOf(HttpHeaders.ETag to "\"bp-v1\"", HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT"),
            )
        }

        EveBlueprintSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(cache.findPage(123, 1))
            .isEqualTo(CachedBlueprintPage(1, "[]", "\"bp-v1\"", Instant.parse("2026-11-08T13:00:00Z"), 1))
    }

    @Test
    fun `an unexpired page is reused without asking ESI again`() = runBlocking<Unit> {
        val cache = MemoryBlueprintPageCache(CachedBlueprintPage(1, ONE_BLUEPRINT, "\"bp-v1\"", now.plusSeconds(60), 1))
        val esi = EsiMock {
            universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val blueprints = EveBlueprintSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(blueprints.map(OwnedBlueprint::itemId)).containsExactly(1001L)
        esi.characterBlueprints(123).assertNotRequested()
    }

    @Test
    fun `an expired page is revalidated and its body reused on not modified`() = runBlocking<Unit> {
        val cache = MemoryBlueprintPageCache(CachedBlueprintPage(1, ONE_BLUEPRINT, "\"bp-v1\"", now.minusSeconds(1), 1))
        val esi = EsiMock {
            characterBlueprints(123).willRespond { request ->
                assertThat(request.headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"bp-v1\"")
                EsiMock.EsiResponse("", HttpStatusCode.NotModified, mapOf(HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT"))
            }
            universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val blueprints = EveBlueprintSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(blueprints.map(OwnedBlueprint::itemId)).containsExactly(1001L)
        assertThat(cache.findPage(123, 1))
            .isEqualTo(CachedBlueprintPage(1, ONE_BLUEPRINT, "\"bp-v1\"", Instant.parse("2026-11-08T13:00:00Z"), 1))
    }

    @Test
    fun `only the expired page of a multi-page list is requested again, and pages the list no longer has are dropped`() =
        runBlocking<Unit> {
            fun body(itemId: Long) = ONE_BLUEPRINT.replace("1001", itemId.toString())
            val cache = MemoryBlueprintPageCache(
                CachedBlueprintPage(1, body(1001), "\"p1\"", now.plusSeconds(60), 2),
                CachedBlueprintPage(2, body(1002), "\"p2\"", now.minusSeconds(1), 2),
                CachedBlueprintPage(3, body(1003), "\"p3\"", now.minusSeconds(1), 2),
            )
            val esi = EsiMock {
                characterBlueprints(123).page(2).willReturn(
                    body(2002),
                    headers = mapOf(HttpHeaders.ETag to "\"p2b\"", HttpHeaders.Expires to "Sun, 08 Nov 2026 13:00:00 GMT", "X-Pages" to "2"),
                )
                universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"}]""")
                universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
            }

            val blueprints = EveBlueprintSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

            assertThat(blueprints.map(OwnedBlueprint::itemId)).containsExactly(1001L, 2002L)
            esi.characterBlueprints(123).assertPagesRequested(2)
            assertThat(cache.pages().map(CachedBlueprintPage::page)).containsExactly(1, 2)
        }

    private class MemoryBlueprintPageCache(vararg initial: CachedBlueprintPage) : BlueprintPageCache {
        private var saved = initial.toList()

        fun pages() = saved

        override fun findPage(characterId: Long, page: Int) = saved.find { it.page == page }

        override fun replace(characterId: Long, pages: List<CachedBlueprintPage>) {
            saved = pages
        }
    }

    private companion object {
        val now: Instant = Instant.parse("2026-11-08T12:00:00Z")
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        const val ONE_BLUEPRINT = """[{"item_id":1001,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
            "material_efficiency":0,"time_efficiency":0,"quantity":-1,"runs":-1}]"""
    }
}
