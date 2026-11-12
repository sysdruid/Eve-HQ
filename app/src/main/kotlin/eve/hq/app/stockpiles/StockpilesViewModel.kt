package eve.hq.app.stockpiles

import java.util.logging.Level
import java.util.logging.Logger
import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetLocation
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemType
import eve.hq.core.production.ListStockpiles
import eve.hq.core.production.Stockpile
import eve.hq.core.production.StockpileKey
import eve.hq.core.production.StockpileRepository
import eve.hq.core.production.StockpileStatus
import eve.hq.core.production.TypeCatalog
import eve.hq.core.production.TypeId
import eve.hq.core.production.StockpileRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StockpileLine(
    val key: StockpileKey,
    val name: String,
    /** The station or structure the stockpile is kept at, if it is tied to one. */
    val station: String?,
    val onHand: Long,
    val required: Long,
    val preferred: Long?,
    val overflow: Long?,
    val status: StockpileStatus,
) {
    val title: String get() = if (station == null) name else "$name @ $station"
}

data class StockpileForm(
    val item: ItemType? = null,
    val suggestions: List<ItemType> = emptyList(),
    /** null: anywhere. */
    val station: AssetLocation? = null,
    /** The stockpile being edited, so a changed station moves it instead of leaving the old one behind. */
    val editing: StockpileKey? = null,
    val required: String = "",
    val preferred: String = "",
    val overflow: String = "",
    val error: String? = null,
)

data class StockpilesUiState(
    val loading: Boolean = false,
    val lines: List<StockpileLine> = emptyList(),
    val failures: List<AssetLoadFailure> = emptyList(),
    val error: String? = null,
    val form: StockpileForm? = null,
    /** Where the assets are: the choices for a stockpile's station. */
    val stations: List<AssetLocation> = emptyList(),
)

private val log = Logger.getLogger(StockpilesViewModel::class.java.name)

class StockpilesViewModel(
    private val listCharacters: ListCharacters,
    private val listStockpiles: ListStockpiles,
    private val stockpiles: StockpileRepository,
    private val catalog: TypeCatalog,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(StockpilesUiState())
    val state: StateFlow<StockpilesUiState> = _state
    fun startAdd() = _state.update { it.copy(form = StockpileForm()) }

    fun searchItems(query: String) {
        scope.launch {
            val found = withContext(io) { catalog.search(query) }
            updateForm { it.copy(suggestions = found) }
        }
    }

    fun chooseItem(item: ItemType) = updateForm { it.copy(item = item, suggestions = emptyList(), error = null) }

    fun chooseStation(station: AssetLocation?) = updateForm { it.copy(station = station, error = null) }

    fun setRequired(text: String) = updateForm { it.copy(required = text, error = null) }

    fun setPreferred(text: String) = updateForm { it.copy(preferred = text, error = null) }

    fun setOverflow(text: String) = updateForm { it.copy(overflow = text, error = null) }

    fun edit(key: StockpileKey) {
        val pile = known[key] ?: return
        _state.update {
            it.copy(
                form = StockpileForm(
                    item = ItemType(pile.item, pile.name),
                    station = pile.station,
                    editing = pile.key,
                    required = pile.required.toString(),
                    preferred = pile.preferred?.toString().orEmpty(),
                    overflow = pile.overflow?.toString().orEmpty(),
                ),
            )
        }
    }

    fun cancel() = _state.update { it.copy(form = null) }

    fun save() {
        val form = _state.value.form ?: return
        val pile = try {
            toStockpile(form)
        } catch (e: IllegalArgumentException) {
            updateForm { it.copy(error = e.message) }
            return
        }
        scope.launch {
            withContext(io) {
                stockpiles.save(pile)
                form.editing?.takeIf { it != pile.key }?.let(stockpiles::remove)
            }
            _state.update { it.copy(form = null) }
            load(restart = true, fetch = false)
        }
    }

    fun remove(key: StockpileKey) {
        scope.launch {
            withContext(io) { stockpiles.remove(key) }
            load(restart = true, fetch = false)
        }
    }

    private fun toStockpile(form: StockpileForm): Stockpile {
        val item = requireNotNull(form.item) { "Choose an item" }
        return Stockpile(
            item = item.id,
            name = item.name,
            required = level(form.required, "Required") ?: throw IllegalArgumentException("Required level must be a whole number"),
            preferred = level(form.preferred, "Preferred"),
            overflow = level(form.overflow, "Overflow"),
            station = form.station,
        )
    }

    /** Blank means "not set"; anything else must be a whole number, optionally with thousands separators. */
    private fun level(text: String, name: String): Long? {
        val digits = text.filterNot { it == ',' || it.isWhitespace() }
        if (digits.isEmpty()) return null
        return digits.toLongOrNull() ?: throw IllegalArgumentException("$name level must be a whole number")
    }

    private fun updateForm(change: (StockpileForm) -> StockpileForm) =
        _state.update { state -> state.copy(form = state.form?.let(change)) }

    private var refreshJob: Job? = null
    private var known = emptyMap<StockpileKey, Stockpile>()

    init {
        load(restart = false, fetch = false)
    }

    /** The Refresh button: asks the asset service to fetch even if its copy is still fresh. */
    fun refresh() = load(restart = true, fetch = true)

    private fun load(restart: Boolean, fetch: Boolean) {
        if (restart) refreshJob?.cancel() else if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val characters = withContext(io) { listCharacters() }
                withContext(io) { listStockpiles.cached(characters) }?.let { saved ->
                    _state.update { it.copy(lines = saved.rows.map(::line), stations = saved.stations) }
                    known = saved.rows.associate { it.stockpile.key to it.stockpile }
                }
                val listing = withContext(io) { listStockpiles(characters, fetch) }
                known = listing.rows.associate { it.stockpile.key to it.stockpile }
                _state.update {
                    it.copy(loading = false, lines = listing.rows.map(::line), failures = listing.failures, stations = listing.stations)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "stockpiles view model failed", e)
                _state.update { it.copy(loading = false, error = e.message ?: "Unable to load stockpiles") }
            }
        }
    }

    private fun line(row: StockpileRow): StockpileLine {
        val pile = row.stockpile
        val station = pile.station?.let { it.name ?: "Location ${it.id}" }
        return StockpileLine(pile.key, pile.name, station, row.onHand, pile.required, pile.preferred, pile.overflow, row.status)
    }
}
