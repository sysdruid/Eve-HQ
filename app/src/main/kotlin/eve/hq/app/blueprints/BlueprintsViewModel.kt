package eve.hq.app.blueprints

import java.util.logging.Level
import java.util.logging.Logger
import eve.hq.app.jobs.asEtl
import eve.hq.app.jobs.code
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintLoadFailure
import eve.hq.core.blueprint.BlueprintRow
import eve.hq.core.blueprint.ListBlueprints
import eve.hq.core.blueprint.SearchBlueprints
import eve.hq.core.character.ListCharacters
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

data class BlueprintLine(val text: String, val inUse: Boolean, val blueprintTypeId: Long, val copy: Boolean)

data class BlueprintsUiState(
    val loading: Boolean = false,
    val query: String = "",
    val freeOnly: Boolean = false,
    val lines: List<BlueprintLine> = emptyList(),
    val failures: List<BlueprintLoadFailure> = emptyList(),
    val error: String? = null,
)

private val log = Logger.getLogger(BlueprintsViewModel::class.java.name)

class BlueprintsViewModel(
    private val listCharacters: ListCharacters,
    private val listBlueprints: ListBlueprints,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(BlueprintsUiState())
    val state: StateFlow<BlueprintsUiState> = _state
    private val search = SearchBlueprints()
    private var rows = emptyList<BlueprintRow>()
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query, lines = lines(query, it.freeOnly)) }

    fun setFreeOnly(freeOnly: Boolean) = _state.update { it.copy(freeOnly = freeOnly, lines = lines(it.query, freeOnly)) }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val characters = withContext(io) { listCharacters() }
                val saved = withContext(io) { listBlueprints.cached(characters) }
                if (saved.rows.isNotEmpty()) {
                    rows = saved.rows
                    _state.update { it.copy(lines = lines(it.query, it.freeOnly)) }
                }
                val listing = withContext(io) { listBlueprints(characters) }
                rows = listing.rows
                _state.update {
                    it.copy(loading = false, lines = lines(it.query, it.freeOnly), failures = listing.failures)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "blueprints view model failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to load blueprints") }
            }
        }
    }

    private fun lines(query: String, freeOnly: Boolean): List<BlueprintLine> {
        val now = clock.instant()
        return search(rows, query, freeOnly).map { row ->
            val kind = if (row.kind == BlueprintKind.Copy) "BPC ${row.remainingRuns} runs" else "BPO"
            val use = row.use?.let {
                val left = Duration.between(now, it.endsAt)
                "  IN USE: ${it.activity.code}, ETL ${if (left.isPositive) left.asEtl() else "done"}"
            }.orEmpty()
            BlueprintLine(
                text = "${row.characterName}  ${row.blueprintName}  $kind  ME ${row.materialEfficiency}  " +
                    "TE ${row.timeEfficiency}  @ ${row.locationName}$use",
                inUse = row.use != null,
                blueprintTypeId = row.blueprintTypeId,
                copy = row.kind == BlueprintKind.Copy,
            )
        }
    }

    private val Duration.isPositive get() = !isZero && !isNegative
}
