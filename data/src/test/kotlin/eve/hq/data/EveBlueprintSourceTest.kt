package eve.hq.data

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.data.esi.EveBlueprintSource
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.time.Instant
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
}
