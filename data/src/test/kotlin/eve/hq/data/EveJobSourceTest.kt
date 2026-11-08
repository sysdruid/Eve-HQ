package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.core.industry.CachedJobPage
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobPageCache
import eve.hq.core.industry.JobStatus
import eve.hq.data.esi.EveJobSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EveJobSourceTest {
    private val alice = EveCharacter(123, "Alice", AuthToken("access", "refresh", Instant.MAX))

    @Test
    fun `an ESI manufacturing job becomes an industry job with resolved names`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn(
                """[{"job_id":9001,"activity_id":1,"blueprint_id":555,"blueprint_type_id":787,"product_type_id":587,
                    "runs":10,"start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"active","facility_id":60003760}]""",
            )
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"},{"id":587,"name":"Rifter"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl).load(alice)

        assertThat(jobs).containsExactly(
            IndustryJob(
                jobId = 9001,
                activity = IndustryActivity.Manufacturing,
                blueprintId = 555,
                blueprint = JobItem(787, "Rifter Blueprint"),
                product = JobItem(587, "Rifter"),
                runs = 10,
                startedAt = Instant.parse("2026-11-08T10:00:00Z"),
                endsAt = Instant.parse("2026-11-08T14:00:00Z"),
                status = JobStatus.Active,
                facility = JobFacility(60003760, "Jita IV - Moon 4"),
            ),
        )
    }

    @Test
    fun `every ESI activity and status maps to its domain value and research jobs have no product`() = runBlocking<Unit> {
        fun job(id: Int, activity: Int, status: String) =
            """{"job_id":$id,"activity_id":$activity,"blueprint_id":555,"blueprint_type_id":787,"runs":1,
                "start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"$status","facility_id":60003760}"""
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn(
                listOf(
                    job(1, 1, "active"), job(2, 3, "paused"), job(3, 4, "ready"), job(4, 5, "delivered"),
                    job(5, 8, "cancelled"), job(6, 9, "reverted"), job(7, 11, "active"),
                ).joinToString(prefix = "[", postfix = "]"),
            )
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl).load(alice)

        assertThat(jobs.map { it.activity to it.status }).containsExactly(
            IndustryActivity.Manufacturing to JobStatus.Active,
            IndustryActivity.TimeEfficiency to JobStatus.Paused,
            IndustryActivity.MaterialEfficiency to JobStatus.Ready,
            IndustryActivity.Copying to JobStatus.Delivered,
            IndustryActivity.Invention to JobStatus.Cancelled,
            IndustryActivity.Reaction to JobStatus.Reverted,
            IndustryActivity.Reaction to JobStatus.Active,
        )
        assertThat(jobs.map { it.product }).containsOnlyNulls()
    }

    @Test
    fun `a job in a player structure resolves the facility name with the character token`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn(
                """[{"job_id":1,"activity_id":1,"blueprint_id":555,"blueprint_type_id":787,"runs":1,
                    "start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"active","facility_id":1035466617946}]""",
            )
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"}]""")
            universeStructures(1035466617946).willReturn("""{"name":"Raitaru of Alice"}""")
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl).load(alice)

        assertThat(jobs.single().facility).isEqualTo(JobFacility(1035466617946, "Raitaru of Alice"))
        esi.assertAnyRequest { it.path.endsWith("/universe/structures/1035466617946/") && it.headers["Authorization"] == "Bearer access" }
    }

    @Test
    fun `a facility whose name cannot be resolved is left unnamed`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn(
                """[{"job_id":1,"activity_id":1,"blueprint_id":555,"blueprint_type_id":787,"runs":1,
                    "start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"active","facility_id":1035466617946}]""",
            )
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"}]""")
            universeStructures(1035466617946).willReturn("""{"error":"Forbidden"}""", HttpStatusCode.Forbidden)
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl).load(alice)

        assertThat(jobs.single().facility).isEqualTo(JobFacility(1035466617946, null))
    }

    @Test
    fun `a forbidden response asks for re-authorization`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn("""{"error":"missing scope"}""", HttpStatusCode.Forbidden)
        }

        val failure = assertFailsWith<LoginRequired> { EveJobSource(esi.client, esi.baseUrl).load(alice) }

        assertThat(failure.message).contains("Add character").contains("job access")
    }

    @Test
    fun `any other failed response reports the endpoint and status`() = runBlocking<Unit> {
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
        }

        val failure = assertFailsWith<Exception> { EveJobSource(esi.client, esi.baseUrl).load(alice) }

        assertThat(failure.message).contains("industry jobs").contains("500")
    }

    @Test
    fun `a fetched job page is saved with its validator and expiry`() = runBlocking<Unit> {
        val cache = MemoryJobPageCache()
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn(
                "[]",
                headers = mapOf(HttpHeaders.ETag to "\"jobs-v1\"", HttpHeaders.Expires to "Sun, 08 Nov 2026 12:05:00 GMT"),
            )
        }

        EveJobSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(cache.find(123)).isEqualTo(CachedJobPage("[]", "\"jobs-v1\"", Instant.parse("2026-11-08T12:05:00Z")))
    }

    @Test
    fun `an unexpired job page is reused without asking ESI again`() = runBlocking<Unit> {
        val cache = MemoryJobPageCache(CachedJobPage(ONE_JOB, "\"jobs-v1\"", now.plusSeconds(60)))
        val esi = EsiMock {
            characterIndustryJobs(123).willReturn("[]")
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(jobs.map(IndustryJob::jobId)).containsExactly(1L)
        esi.characterIndustryJobs(123).assertNotRequested()
    }

    @Test
    fun `an expired job page is revalidated and its body reused on not modified`() = runBlocking<Unit> {
        val cache = MemoryJobPageCache(CachedJobPage(ONE_JOB, "\"jobs-v1\"", now.minusSeconds(1)))
        val esi = EsiMock {
            characterIndustryJobs(123).willRespond { request ->
                assertThat(request.headers[HttpHeaders.IfNoneMatch]).isEqualTo("\"jobs-v1\"")
                EsiMock.EsiResponse(
                    "",
                    HttpStatusCode.NotModified,
                    mapOf(HttpHeaders.Expires to "Sun, 08 Nov 2026 12:05:00 GMT"),
                )
            }
            universeNames().willReturn("""[{"id":787,"name":"Rifter Blueprint"}]""")
            universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""")
        }

        val jobs = EveJobSource(esi.client, esi.baseUrl, pageCache = cache, clock = clock).load(alice)

        assertThat(jobs.map(IndustryJob::jobId)).containsExactly(1L)
        assertThat(cache.find(123)).isEqualTo(CachedJobPage(ONE_JOB, "\"jobs-v1\"", Instant.parse("2026-11-08T12:05:00Z")))
    }

    private class MemoryJobPageCache(initial: CachedJobPage? = null) : JobPageCache {
        private var page: CachedJobPage? = initial

        override fun find(characterId: Long) = page

        override fun save(characterId: Long, page: CachedJobPage) {
            this.page = page
        }
    }

    private companion object {
        val now: Instant = Instant.parse("2026-11-08T12:00:00Z")
        val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
        const val ONE_JOB = """[{"job_id":1,"activity_id":1,"blueprint_id":555,"blueprint_type_id":787,"runs":1,
            "start_date":"2026-11-08T10:00:00Z","end_date":"2026-11-08T14:00:00Z","status":"active","facility_id":60003760}]"""
    }
}
