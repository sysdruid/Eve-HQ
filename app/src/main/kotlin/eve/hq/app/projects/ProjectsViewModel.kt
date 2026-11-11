package eve.hq.app.projects

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.PlanProject
import eve.hq.core.production.ProjectId
import eve.hq.core.production.StockpileStatus
import eve.hq.app.jobs.asEtl
import eve.hq.core.production.ItemDemand
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
import java.time.Duration
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

data class PhaseBlock(val title: String, val jobs: List<String>)

data class ShoppingLine(val text: String, val status: StockpileStatus)

data class ProjectsUiState(
    val loading: Boolean = false,
    val title: String = "",
    val goals: List<String> = emptyList(),
    val phases: List<PhaseBlock> = emptyList(),
    val shopping: List<ShoppingLine> = emptyList(),
    val failures: List<AssetLoadFailure> = emptyList(),
    val error: String? = null,
)

private val log = Logger.getLogger(ProjectsViewModel::class.java.name)

class ProjectsViewModel(
    private val listCharacters: ListCharacters,
    private val planProject: PlanProject,
    private val catalog: ItemCatalog,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state
    private var refreshJob: Job? = null
    private val project = ProjectId.AmarrShuttles

    init {
        refresh(fetch = false)
    }

    /** [fetch] false accepts the assets the service already has fresh, as opening the tab does; the button forces it. */
    fun refresh(fetch: Boolean = true) {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val characters = withContext(io) { listCharacters() }
                val result = withContext(io) { planProject(project, characters, refresh = fetch) }
                _state.value = ProjectsUiState(
                    title = project.title,
                    goals = result.goals.map { "${count(it.required)} × ${name(it.item)}" },
                    phases = result.plan.phases.map { phase ->
                        PhaseBlock("Phase ${phase.number}", phase.jobs.map(::jobLine))
                    },
                    shopping = result.plan.shoppingList.map(::shoppingLine),
                    failures = result.failures,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "projects view model failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to plan the project") }
            }
        }
    }

    private fun jobLine(job: PlannedJob): String {
        val runs = if (job.runs == 1L) "1 run" else "${count(job.runs)} runs"
        val each = Duration.ofSeconds(job.secondsPerRun.toLong()).asEtl()
        return "${name(job.blueprint)}  $runs × $each  → ${name(job.product)}"
    }

    private fun shoppingLine(demand: ItemDemand) = ShoppingLine(
        "${name(demand.item)}  need ${count(demand.required)}  on hand ${count(demand.onHand)}  short ${count(demand.deficit)}",
        demand.status,
    )

    private fun name(item: TypeId) = catalog.itemName(item) ?: "Type $item"

    private fun count(value: Long) = "%,d".format(Locale.ROOT, value)
}
