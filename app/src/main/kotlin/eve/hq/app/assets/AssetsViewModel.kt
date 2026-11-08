package eve.hq.app.assets

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetRow
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
    val error: String? = null,
)

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
        refresh()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, items = searchAssets(allItems, query)) }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val listing = withContext(io) { listAssets(listCharacters()) }
                allItems = listing.items
                _state.update {
                    it.copy(loading = false, items = searchAssets(allItems, it.query), failures = listing.failures)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to load assets") }
            }
        }
    }
}