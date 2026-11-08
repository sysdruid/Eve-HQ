package eve.hq.features

import eve.hq.core.character.AuthToken
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.JobRow
import eve.hq.core.industry.JobStatus
import eve.hq.data.EsiMock
import eve.hq.features.support.FeatureSystem
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test

class ListJobsFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `an expired token is refreshed and the character's running jobs are listed`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID") } // default token is long expired
        system.refresher.next = { AuthToken("fresh-access", "rotated", system.now.plusSeconds(3600)) }
        system.esi.aliceHasOneManufacturingJob()

        val result = system.listJobs()

        assertThat(result.failures).isEmpty()
        assertThat(result.rows).containsExactly(
            JobRow(
                characterName = "Alice",
                activity = IndustryActivity.Manufacturing,
                itemName = "Rifter",
                runs = 10,
                startedAt = Instant.parse("2026-11-08T10:00:00Z"),
                endsAt = Instant.parse("2026-11-08T14:00:00Z"),
                status = JobStatus.Active,
                facilityName = "Jita IV - Moon 4",
            ),
        )
        system.esi.assertAnyRequest {
            it.path.endsWith("/characters/1/industry/jobs/") && it.headers[HttpHeaders.Authorization] == "Bearer fresh-access"
        }
    }

    @Test
    fun `a revoked refresh token is reported as a failure that asks to log in again`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID") }
        system.refresher.next = { throw eve.hq.core.character.TokenRevoked("revoked") }

        val result = system.listJobs()

        assertThat(result.rows).isEmpty()
        assertThat(result.failures.single().message).containsIgnoringCase("log in again")
    }

    @Test
    fun `a job page that has not expired is served from the database after a restart`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID").token_expires_at(system.now.plusSeconds(3600)) }
        system.esi.aliceHasOneManufacturingJob(expires = "Sun, 08 Nov 2026 12:05:00 GMT")
        system.listJobs()

        system.reopenJobServices()
        val afterRestart = system.listJobs()

        assertThat(afterRestart.rows.map(JobRow::itemName)).containsExactly("Rifter")
        assertThat(system.esi.requests.count { it.path.endsWith("/industry/jobs/") }).isEqualTo(1)
    }

    @Test
    fun `a facility name is fetched once and reused after a restart`() = runBlocking<Unit> {
        system.given { characters().id(1).name("Alice").status("VALID").token_expires_at(system.now.plusSeconds(3600)) }
        system.esi.aliceHasOneManufacturingJob(expires = "Sun, 08 Nov 2026 12:05:00 GMT")
        system.listJobs()

        system.reopenJobServices()
        val afterRestart = system.listJobs()

        assertThat(afterRestart.rows.single().facilityName).isEqualTo("Jita IV - Moon 4")
        assertThat(system.esi.requests.count { it.path.endsWith("/universe/stations/60003760/") }).isEqualTo(1)
    }

    private fun EsiMock.aliceHasOneManufacturingJob(expires: String? = null) {
        characterIndustryJobs(1).willReturn(
            """[{"job_id":9001,"activity_id":1,"blueprint_id":555,"blueprint_type_id":787,"product_type_id":587,
                "runs":10,"start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"active","facility_id":60003760}]""",
            headers = listOfNotNull(expires?.let { HttpHeaders.Expires to it }).toMap(),
        )
        universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"},{"id":587,"name":"Rifter"}]""")
        universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
    }
}
