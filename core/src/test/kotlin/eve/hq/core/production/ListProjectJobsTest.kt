package eve.hq.core.production

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import eve.hq.core.industry.ListJobs
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

class ListProjectJobsTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val shuttle = 11134L
    private val rifter = 587L
    private val catalog = FakeItemCatalog(names = mapOf(shuttle to "Amarr Shuttle", rifter to "Rifter"))

    private fun job(id: Long, product: Long, name: String, status: JobStatus = JobStatus.Active) = IndustryJob(
        jobId = id, activity = IndustryActivity.Manufacturing, blueprintId = id, blueprint = JobItem(id, "$name Blueprint"),
        product = JobItem(product, name), runs = 10, startedAt = now.minusSeconds(60), endsAt = now.plusSeconds(3600),
        status = status, facility = JobFacility(1, "Raitaru"),
    )

    private var running = listOf(job(1, shuttle, "Amarr Shuttle"), job(2, rifter, "Rifter"), job(3, shuttle, "Amarr Shuttle", JobStatus.Delivered))
    private val source = object : JobSource {
        override suspend fun load(character: EveCharacter) = running

        override fun cached(character: EveCharacter) = running
    }
    private val listProjectJobs = ListProjectJobs(
        Projects(Items(catalog), RecipeBook { null }),
        ListJobs(source, Clock.fixed(now, ZoneOffset.UTC)),
    )

    @Test
    fun `only running jobs that make the project's end products are listed`() = runBlocking<Unit> {
        val listing = listProjectJobs(ProjectId.AmarrShuttles, listOf(alice))

        assertThat(listing.rows.map { it.itemName }).containsExactly("Amarr Shuttle")
        assertThat(listing.failures).isEmpty()
    }

    @Test
    fun `the saved jobs are filtered the same way without asking the source`() {
        val listing = listProjectJobs.cached(ProjectId.AmarrShuttles, listOf(alice))

        assertThat(listing.rows.map { it.productId }).containsExactly(shuttle)
    }
}
