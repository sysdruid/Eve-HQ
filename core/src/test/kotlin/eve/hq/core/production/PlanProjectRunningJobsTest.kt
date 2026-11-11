package eve.hq.core.production

import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

/** Output of running jobs lands in the hangars during the cycle, so it counts as stock for the plan. */
class PlanProjectRunningJobsTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val shuttle = 11134L
    private val tritanium = 34L
    private val zealot = 11000L
    private val plate = 11543L

    private val catalog = FakeItemCatalog(
        names = mapOf(shuttle to "Amarr Shuttle", tritanium to "Tritanium", zealot to "Zealot", plate to "Plate"),
        groups = mapOf(plate to "Construction Components"),
    )
    private val recipes = RecipeBook { product ->
        when (product) {
            shuttle -> Recipe(shuttle, 1, mapOf(tritanium to 2778), blueprint = 11135, secondsPerRun = 6000)
            zealot -> Recipe(zealot, 1, mapOf(plate to 300), blueprint = 11001, secondsPerRun = 240_000)
            plate -> Recipe(plate, 2, mapOf(tritanium to 100), blueprint = 11544, secondsPerRun = 600)
            else -> null
        }
    }
    private val medium = ReadinessConfig(
        hulls = listOf("Zealot"),
        cycle = CycleSettings(Duration.ofDays(14), window = Duration.ofHours(12)),
        jobs = 1,
    )

    private var running: List<IndustryJob> = emptyList()
    private var jobsFail = false
    private val jobSource = JobSource { if (jobsFail) error("ESI jobs are down") else running }
    private val planProject = PlanProject(
        Projects(Items(catalog), recipes, medium),
        recipes,
        AssetService(AssetSource { emptyList() }, clock),
        ListJobs(jobSource, clock),
        clock,
    )

    private fun job(
        id: Long,
        product: Long,
        runs: Int,
        endsIn: Duration,
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        status: JobStatus = JobStatus.Active,
    ) = IndustryJob(
        jobId = id, activity = activity, blueprintId = id, blueprint = JobItem(id, "Blueprint $id"),
        product = JobItem(product, "Product $product"), runs = runs, startedAt = now.minusSeconds(3600),
        endsAt = now.plus(endsIn), status = status, facility = JobFacility(1, "Raitaru"),
    )

    private fun plan(id: ProjectId) = runBlocking { planProject(id, listOf(alice)) }

    @Test
    fun `the output of a running job counts as stock, so the plan asks for less`() {
        running = listOf(job(1, shuttle, runs = 300, endsIn = Duration.ofHours(1)))

        val result = plan(ProjectId.AmarrShuttles)

        assertThat(result.plan.jobs.single().runs).isEqualTo(700)
    }

    @Test
    fun `a project without a cycle counts every running job, however late it ends`() {
        running = listOf(job(1, shuttle, runs = 300, endsIn = Duration.ofDays(60)))

        assertThat(plan(ProjectId.AmarrShuttles).plan.jobs.single().runs).isEqualTo(700)
    }

    @Test
    fun `with a cycle only jobs ending within its usable length count`() {
        // 4 runs of 300 plates fit in 14 days less 12 hours: 1200 plates wanted, 2 per run.
        running = listOf(
            job(1, plate, runs = 100, endsIn = Duration.ofDays(1)), // 200 plates, in time
            job(2, plate, runs = 100, endsIn = Duration.ofDays(20)), // too late for this cycle
        )

        val result = plan(ProjectId.MediumT2Hulls)

        assertThat(result.plan.jobs.single { it.product == plate }.runs).isEqualTo(500) // (1200 - 200) / 2
    }

    @Test
    fun `a finished job that was not delivered yet still counts, it only waits to be claimed`() {
        running = listOf(job(1, shuttle, runs = 300, endsIn = Duration.ofHours(-2), status = JobStatus.Active))

        assertThat(plan(ProjectId.AmarrShuttles).plan.jobs.single().runs).isEqualTo(700)
    }

    @Test
    fun `jobs that make no items, and delivered ones, add nothing`() {
        running = listOf(
            job(1, shuttle, runs = 300, endsIn = Duration.ofHours(1), activity = IndustryActivity.Copying),
            job(2, shuttle, runs = 300, endsIn = Duration.ofHours(1), activity = IndustryActivity.Invention),
            job(3, shuttle, runs = 300, endsIn = Duration.ofHours(-5), status = JobStatus.Delivered),
        )

        assertThat(plan(ProjectId.AmarrShuttles).plan.jobs.single().runs).isEqualTo(1_000)
    }

    @Test
    fun `the output per run of the recipe is counted, not just the runs`() {
        running = listOf(job(1, plate, runs = 100, endsIn = Duration.ofDays(1)))

        val result = plan(ProjectId.MediumT2Hulls)

        assertThat(result.plan.demands.single { it.item == plate }.onHand).isEqualTo(200) // 100 runs x 2 per run
    }

    @Test
    fun `a job load that fails is reported and the plan carries on with the assets alone`() {
        jobsFail = true

        val result = plan(ProjectId.AmarrShuttles)

        assertThat(result.jobFailures.single().message).isEqualTo("ESI jobs are down")
        assertThat(result.plan.jobs.single().runs).isEqualTo(1_000)
    }

    @Test
    fun `the result carries the running jobs and what the project produces, for its tab`() {
        running = listOf(job(1, shuttle, runs = 300, endsIn = Duration.ofHours(1)))

        val result = plan(ProjectId.AmarrShuttles)

        assertThat(result.endProducts).containsExactly(shuttle)
        assertThat(result.running.rows.map { it.productId }).containsExactly(shuttle)
    }
}
