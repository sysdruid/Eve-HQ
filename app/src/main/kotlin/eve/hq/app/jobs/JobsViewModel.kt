package eve.hq.app.jobs

import eve.hq.core.character.ListCharacters
import eve.hq.core.industry.JobLoadFailure
import eve.hq.core.industry.JobRow
import eve.hq.core.industry.JobStatus
import eve.hq.core.industry.ListJobs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

enum class JobLineStatus(val label: String) { Running("Active"), Paused("Paused"), Ready("Ready") }

data class JobLine(val text: String, val status: JobLineStatus, val progress: Float)

data class JobsUiState(
    val loading: Boolean = false,
    val lines: List<JobLine> = emptyList(),
    val failures: List<JobLoadFailure> = emptyList(),
    val error: String? = null,
)

class JobsViewModel(
    private val listCharacters: ListCharacters,
    private val listJobs: ListJobs,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(JobsUiState())
    val state: StateFlow<JobsUiState> = _state
    private var rows = emptyList<JobRow>()
    private var refreshJob: Job? = null

    init {
        refresh()
        scope.launch {
            while (true) {
                delay(COUNTDOWN_TICK_MILLIS)
                _state.update { it.copy(lines = lines()) }
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val listing = withContext(io) { listJobs(listCharacters()) }
                rows = listing.rows
                _state.update { it.copy(loading = false, lines = lines(), failures = listing.failures) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to load jobs") }
            }
        }
    }

    private fun lines(): List<JobLine> {
        val now = clock.instant()
        return rows.map { row ->
            val ready = row.isReady(now)
            val etl = if (ready) "done" else row.remaining(now).asEtl()
            val status = when {
                ready -> JobLineStatus.Ready
                row.status == JobStatus.Paused -> JobLineStatus.Paused
                else -> JobLineStatus.Running
            }
            JobLine(
                text = "${row.characterName}-${row.itemName} [${row.runs}] ${row.activity.code}  ETL: $etl  ${status.label} @ ${row.facilityName}",
                status = status,
                progress = row.progress(now).toFloat(),
            )
        }
    }

    private companion object {
        const val COUNTDOWN_TICK_MILLIS = 5_000L
    }
}
