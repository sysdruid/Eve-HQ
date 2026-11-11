package eve.hq.app.projects

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.character.ListCharacters
import eve.hq.app.jobs.JobLine
import eve.hq.app.jobs.toLine
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.JobLoadFailure
import eve.hq.core.industry.JobRow
import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.TimeInputFailure
import eve.hq.core.production.PlanProject
import eve.hq.core.production.ProjectId
import eve.hq.core.production.StockpileStatus
import eve.hq.app.jobs.asEtl
import eve.hq.core.production.PlannedJob
import eve.hq.core.production.TypeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** One phase: the jobs the plan still asks for, and the running and finished (undelivered) jobs making its products. */
data class PhaseBlock(
    val title: String,
    val toStart: List<String>,
    val running: List<JobLine> = emptyList(),
    val ready: List<JobLine> = emptyList(),
)

data class ProjectChoice(val id: ProjectId, val title: String)

data class ProjectsUiState(
    val projects: List<ProjectChoice> = ProjectId.entries.map { ProjectChoice(it, it.title) },
    val selected: ProjectId = ProjectId.entries.first(),
    val loading: Boolean = false,
    val title: String = "",
    val goals: List<String> = emptyList(),
    val phases: List<PhaseBlock> = emptyList(),
    /** What the plan does not produce and is short of, by item group, with multibuy text per group. */
    val prerequisites: Prerequisites = Prerequisites(emptyList(), ""),
    val failures: List<AssetLoadFailure> = emptyList(),
    /** Running jobs making the project's end products (the hulls), as the Jobs tab shows them. */
    val hullJobs: List<JobLine> = emptyList(),
    val hullJobsSummary: String = "",
    val jobFailures: List<JobLoadFailure> = emptyList(),
    /** Characters whose skills or blueprints could not be read: job times may then be longer than in the game. */
    val timeFailures: List<TimeInputFailure> = emptyList(),
    val error: String? = null,
)

private val log = Logger.getLogger(ProjectsViewModel::class.java.name)

class ProjectsViewModel(
    private val listCharacters: ListCharacters,
    private val planProject: PlanProject,
    private val catalog: ItemCatalog,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state
    private var refreshJob: Job? = null

    init {
        plan(fetch = false, restart = false)
    }

    fun select(id: ProjectId) {
        _state.update { it.copy(selected = id) }
        plan(fetch = false, restart = true)
    }

    /** The Refresh button: asks the asset service to fetch even if its copy is still fresh. */
    fun refresh() = plan(fetch = true, restart = false)

    private fun plan(fetch: Boolean, restart: Boolean) {
        if (restart) refreshJob?.cancel() else if (refreshJob?.isActive == true) return
        val project = _state.value.selected
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val characters = withContext(io) { listCharacters() }
                val result = withContext(io) { planProject(project, characters, refresh = fetch) }
                val now = clock.instant()
                val hullRows = result.running.rows.filter { it.productId in result.endProducts }
                _state.update {
                    it.copy(
                        loading = false,
                        title = project.title,
                        goals = result.goals.map { goal -> "${count(goal.required)} × ${name(goal.item)}" },
                        phases = result.plan.phases.map { phase ->
                            val making = result.running.rows.filter { it.productId in phase.products && it.makesItems() }
                            val (done, going) = making.partition { it.isReady(now) }
                            PhaseBlock(
                                title = "Phase ${phase.number}",
                                toStart = phase.jobs.map(::jobLine),
                                running = going.map { row -> row.toLine(now) },
                                ready = done.map { row -> row.toLine(now) },
                            )
                        },
                        prerequisites = groupPrerequisites(result.plan.prerequisites, catalog),
                        failures = result.failures,
                        hullJobs = hullRows.map { row -> row.toLine(now) },
                        hullJobsSummary = summary(hullRows),
                        jobFailures = result.jobFailures,
                        timeFailures = result.timeFailures,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "projects view model failed", e)
                _state.update {
                    it.copy(
                        loading = false,
                        title = project.title,
                        goals = emptyList(),
                        phases = emptyList(),
                        prerequisites = Prerequisites(emptyList(), ""),
                        failures = emptyList(),
                        hullJobs = emptyList(),
                        hullJobsSummary = "",
                        jobFailures = emptyList(),
                        timeFailures = emptyList(),
                        error = e.message ?: "Unable to plan the project",
                    )
                }
            }
        }
    }

    /** "Legion 8, Tengu 8 in progress (2 jobs)": runs of a hull job are hulls, summed per product. */
    private fun JobRow.makesItems() = activity == IndustryActivity.Manufacturing || activity == IndustryActivity.Reaction

    private fun summary(rows: List<JobRow>): String {
        if (rows.isEmpty()) return ""
        val perProduct = rows.groupBy { it.itemName }.map { (name, jobs) -> "$name ${count(jobs.sumOf { it.runs }.toLong())}" }
        val jobs = if (rows.size == 1) "1 job" else "${rows.size} jobs"
        return "${perProduct.joinToString(", ")} in progress ($jobs)"
    }

    private fun jobLine(job: PlannedJob): String {
        val runs = if (job.runs == 1L) "1 run" else "${count(job.runs)} runs"
        val each = Duration.ofSeconds(job.secondsPerRun.toLong()).asEtl()
        val total = Duration.ofSeconds(job.totalSeconds).asEtl()
        return "${name(job.blueprint)}  $runs × $each = $total  → ${name(job.product)}"
    }

    private fun name(item: TypeId) = catalog.itemName(item) ?: "Type $item"

    private fun count(value: Long) = "%,d".format(Locale.ROOT, value)
}
