package eve.hq.app.stockpiles

import java.util.logging.Level
import java.util.logging.Logger
import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemType
import eve.hq.core.production.ListStockpiles
import eve.hq.core.production.Stockpile
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
import java.util.Locale

data class StockpileLine(val item: TypeId, val text: String, val status: StockpileStatus)

data class StockpileForm(
    val item: ItemType? = null,
    val suggestions: List<ItemType> = emptyList(),
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

    fun setRequired(text: String) = updateForm { it.copy(required = text, error = null) }

    fun setPreferred(text: String) = updateForm { it.copy(preferred = text, error = null) }

    fun setOverflow(text: String) = updateForm { it.copy(overflow = text, error = null) }

    fun edit(item: TypeId) {
        val pile = known[item] ?: return
        _state.update {
            it.copy(
                form = StockpileForm(
                    item = ItemType(pile.item, pile.name),
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
            withContext(io) { stockpiles.save(pile) }
            _state.update { it.copy(form = null) }
            refresh(force = true)
        }
    }

    fun remove(item: TypeId) {
        scope.launch {
            withContext(io) { stockpiles.remove(item) }
            refresh(force = true)
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
    private var known = emptyMap<TypeId, Stockpile>()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        if (force) refreshJob?.cancel() else if (refreshJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        refreshJob = scope.launch {
            try {
                val listing = withContext(io) { listStockpiles(listCharacters()) }
                known = listing.rows.associate { it.stockpile.item to it.stockpile }
                _state.update {
                    it.copy(loading = false, lines = listing.rows.map(::line), failures = listing.failures)
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
        val levels = listOfNotNull(
            "required ${count(pile.required)}",
            pile.preferred?.let { "preferred ${count(it)}" },
            pile.overflow?.let { "overflow ${count(it)}" },
        )
        return StockpileLine(pile.item, "${pile.name}  on hand ${count(row.onHand)}  ${levels.joinToString("  ")}", row.status)
    }

    private fun count(value: Long) = "%,d".format(Locale.ROOT, value)
}
