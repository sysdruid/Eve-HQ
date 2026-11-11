package eve.hq.features

import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintRow
import eve.hq.core.blueprint.BlueprintUse
import eve.hq.core.character.AuthToken
import eve.hq.core.industry.IndustryActivity
import eve.hq.data.EsiMock
import eve.hq.features.support.FeatureSystem
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test

class ListBlueprintsFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `an expired token is refreshed and the blueprints show which one a running job uses`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID") } // default token is long expired
        system.refresher.next = { AuthToken("fresh-access", "rotated", system.now.plusSeconds(3600)) }
        system.esi.aliceOwnsOneCopyAndOneOriginal()

        val result = system.listBlueprints()

        assertThat(result.failures).isEmpty()
        assertThat(result.rows).containsExactly(
            BlueprintRow(
                1001, "Alice", "Rifter Blueprint", BlueprintKind.Original, 10, 20, null, "Jita IV - Moon 4",
                use = BlueprintUse(IndustryActivity.Manufacturing, Instant.parse("2026-11-08T14:00:00Z")), blueprintTypeId = 681,
            ),
            BlueprintRow(1002, "Alice", "Rifter Blueprint", BlueprintKind.Copy, 2, 4, 4, "Jita IV - Moon 4", use = null, blueprintTypeId = 681),
        )
        system.esi.assertAnyRequest {
            it.path.endsWith("/characters/1/blueprints/") && it.headers[HttpHeaders.Authorization] == "Bearer fresh-access"
        }
    }

    @Test
    fun `a blueprint page that has not expired is served from the database on the next listing`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID").token_expires_at(system.now.plusSeconds(3600)) }
        system.esi.aliceOwnsOneCopyAndOneOriginal(expires = "Sun, 08 Nov 2026 13:00:00 GMT")
        system.listBlueprints()

        val again = system.listBlueprints()

        assertThat(again.rows.map(BlueprintRow::itemId)).containsExactly(1001L, 1002L)
        assertThat(system.esi.requests.count { it.path.endsWith("/characters/1/blueprints/") }).isEqualTo(1)
    }

    @Test
    fun `after a restart the last blueprints are listed from the database with their in-use marks and no ESI request`() =
        runBlocking<Unit> {
            system.given { characters().id(1).name("Alice").status("VALID").token_expires_at(system.now.plusSeconds(3600)) }
            system.givenStaticTypes(681L to "Rifter Blueprint", 587L to "Rifter")
            system.esi.aliceOwnsOneCopyAndOneOriginal(expires = "Sun, 08 Nov 2026 13:00:00 GMT")
            val live = system.listBlueprints()
            val requestsBefore = system.esi.requests.size

            val cached = system.cachedBlueprints()

            assertThat(cached.rows).containsExactlyElementsOf(live.rows)
            assertThat(system.esi.requests).hasSize(requestsBefore)
        }

    private fun EsiMock.aliceOwnsOneCopyAndOneOriginal(expires: String? = null) {
        characterBlueprints(1).willReturn(
            """[{"item_id":1001,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
                "material_efficiency":10,"time_efficiency":20,"quantity":-1,"runs":-1},
                {"item_id":1002,"type_id":681,"location_id":60003760,"location_flag":"Hangar",
                "material_efficiency":2,"time_efficiency":4,"quantity":-2,"runs":4}]""",
            headers = listOfNotNull(expires?.let { HttpHeaders.Expires to it }).toMap(),
        )
        characterIndustryJobs(1).willReturn(
            """[{"job_id":9001,"activity_id":1,"blueprint_id":1001,"blueprint_type_id":681,"product_type_id":587,
                "runs":10,"start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z",
                "status":"active","facility_id":60003760}]""",
        )
        universeNames().willReturn("""[{"id":681,"name":"Rifter Blueprint"},{"id":587,"name":"Rifter"}]""")
        universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
    }
}
