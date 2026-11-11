package eve.hq.app.assets

import java.util.logging.Level
import java.util.logging.Logger
import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetRow
import eve.hq.core.asset.AssetSnapshotInfo
import eve.hq.core.asset.ListAssets
import eve.hq.core.asset.SearchAssets
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

data class AssetsUiState(
    val loading: Boolean = false,
    val query: String = "",
    val items: List<AssetRow> = emptyList(),
    val failures: List<AssetLoadFailure> = emptyList(),
    val snapshots: List<AssetSnapshotInfo> = emptyList(),
    val error: String? = null,
)

private val log = Logger.getLogger(AssetsViewModel::class.java.name)

class AssetsViewModel(
    private val listCharacters: ListCharacters,
    private val listAssets: ListAssets,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(AssetsUiState())
    val state: StateFlow<AssetsUiState> = _state
    private val searchAssets = SearchAssets()
    private var allItems = emptyList<AssetRow>()
    private var refreshJob: Job? = null

    init {
        refresh(fetch = false)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, items = searchAssets(allItems, query)) }
    }

    /** [fetch] false accepts what the asset service already has fresh, as opening the tab does; the button forces it. */
    fun refresh(fetch: Boolean = true) {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val characters = withContext(io) { listCharacters() }
                val cached = withContext(io) { listAssets.cached(characters) }
                if (cached.snapshots.isNotEmpty()) {
                    allItems = cached.items
                    _state.update { it.copy(items = searchAssets(allItems, it.query), snapshots = cached.snapshots) }
                }
                val listing = withContext(io) { listAssets(characters, refresh = fetch) }
                val failedCharacters = listing.failures.mapTo(mutableSetOf(), AssetLoadFailure::characterName)
                val returnedCharacters = listing.items.mapTo(mutableSetOf(), AssetRow::characterName)
                allItems = listing.items + allItems.filter {
                    it.characterName in failedCharacters && it.characterName !in returnedCharacters
                }
                _state.update {
                    it.copy(
                        loading = false,
                        items = searchAssets(allItems, it.query),
                        failures = listing.failures,
                        snapshots = listing.snapshots,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "assets view model failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to load assets") }
            }
        }
    }
}