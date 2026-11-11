package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetService
import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.JobListing
import eve.hq.core.industry.JobLoadFailure
import eve.hq.core.industry.JobRow
import eve.hq.core.industry.ListJobs
import java.time.Clock
import java.time.Instant

/** A project's plan, and the asset loads that failed while working out what is on hand (so stock may read low). */
data class ProjectPlan(
    val goals: List<Requirement>,
    val plan: Plan,
    val failures: List<AssetLoadFailure>,
    /** Every running job, loaded once for the plan's stock and for the project's tab. */
    val running: JobListing,
    val jobFailures: List<JobLoadFailure>,
    val endProducts: Set<TypeId>,
)

class PlanProject(
    private val projects: Projects,
    private val recipes: RecipeBook,
    private val assets: AssetService,
    private val listJobs: ListJobs,
    private val clock: Clock,
) {
    /**
     * Plans the project alone against the pooled assets plus the output of running jobs that will land in the hangars
     * within the project's usable cycle (all of them when it has no cycle); manual stockpiles are not part of it.
     */
    suspend operator fun invoke(id: ProjectId, characters: List<EveCharacter>, refresh: Boolean = false): ProjectPlan {
        val owned = assets.assets(characters, refresh = refresh)
        val running = listJobs(characters)
        val project = projects.get(id)
        val until = project.cycle?.let { clock.instant().plus(it.usable) }
        val stock = owned.quantities().toMutableMap()
        runningOutput(running.rows, until).forEach { (item, quantity) -> stock.merge(item, quantity, Long::plus) }
        val plan = PlanProduction(recipes)(listOf(project), stock = stock)
        return ProjectPlan(project.requirements(), plan, owned.failures, running, running.failures, project.endProducts)
    }

    /** What running jobs ending by [until] will deliver. Delivered jobs are not listed; invention and copying make no items. */
    private fun runningOutput(rows: List<JobRow>, until: Instant?): Map<TypeId, Long> = rows
        .filter { it.activity == IndustryActivity.Manufacturing || it.activity == IndustryActivity.Reaction }
        .filter { until == null || !it.endsAt.isAfter(until) }
        .mapNotNull { row -> row.productId?.let { product -> product to row.runs * outputPerRun(product) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, outputs) -> outputs.sum() }

    // One odd product must not take the whole plan down: a product with several published blueprints counts as one per run.
    private fun outputPerRun(product: TypeId): Long = runCatching { recipes.forProduct(product)?.perRun }.getOrNull()?.toLong() ?: 1L
}
