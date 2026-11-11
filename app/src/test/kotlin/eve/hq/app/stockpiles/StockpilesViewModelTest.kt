package eve.hq.app.stockpiles

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSnapshot
import eve.hq.core.asset.AssetSource
import eve.hq.core.character.EveCharacter
import java.time.Instant
import eve.hq.core.asset.AssetType
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemType
import eve.hq.core.production.ListStockpiles
import eve.hq.core.production.Stockpile
import eve.hq.core.production.StockpileKey
import eve.hq.core.production.StockpileRepository
import eve.hq.core.production.StockpileStatus
import eve.hq.core.production.TypeCatalog
import eve.hq.core.production.TypeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockpilesViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)

    private val saved = linkedMapOf<StockpileKey, Stockpile>()
    private val repository = object : StockpileRepository {
        override fun all() = saved.values.toList()

        override fun save(stockpile: Stockpile) {
            saved[stockpile.key] = stockpile
        }

        override fun remove(key: StockpileKey) {
            saved.remove(key)
        }
    }
    private val crystals = ItemType(100, "Multifrequency L")
    private val catalog = TypeCatalog { query -> listOf(crystals).filter { it.name.contains(query, ignoreCase = true) } }
    private var assets: suspend () -> List<AssetItem> = { emptyList() }

    private var savedAssets: List<AssetItem>? = null
    private val source = object : AssetSource {
        override suspend fun load(character: EveCharacter) = assets()

        override fun cachedSnapshot(characterId: Long) =
            savedAssets?.let { AssetSnapshot(it, Instant.parse("2026-11-11T10:00:00Z")) }
    }

    private fun viewModel() = StockpilesViewModel(
        ListCharacters(system.repository),
        ListStockpiles(repository, source),
        repository,
        catalog,
        scope,
        dispatcher,
    )

    private fun asset(itemId: Long, type: TypeId, quantity: Int) =
        AssetItem(itemId, AssetType(type, null), null, quantity, AssetLocation(9000, null))

    @Test
    fun `loading is visible until the assets arrive, then lines show on hand, levels and status`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100, preferred = 300)
        val pending = CompletableDeferred<List<AssetItem>>()
        assets = { pending.await() }

        val viewModel = viewModel()

        assertThat(viewModel.state.value.loading).isTrue()
        pending.complete(listOf(asset(1, 100, 1_210)))
        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.lines).containsExactly(
            StockpileLine(StockpileKey(100), "Multifrequency L", "Multifrequency L  on hand 1,210  required 100  preferred 300", StockpileStatus.Ok),
        )
    }

    @Test
    fun `saved assets are listed while the fresh load is still pending`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100)
        savedAssets = listOf(asset(1, 100, 40))
        val pending = CompletableDeferred<List<AssetItem>>()
        assets = { pending.await() }

        val viewModel = viewModel()

        assertThat(viewModel.state.value.loading).isTrue()
        assertThat(viewModel.state.value.lines.single().text).endsWith("on hand 40  required 100")
        pending.complete(listOf(asset(1, 100, 90)))
        assertThat(viewModel.state.value.lines.single().text).endsWith("on hand 90  required 100")
    }

    @Test
    fun `without saved assets nothing is listed until the load arrives`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100)
        assets = { CompletableDeferred<List<AssetItem>>().await() }

        assertThat(viewModel().state.value.lines).isEmpty()
    }

    @Test
    fun `a character that fails to load is reported while the stockpiles still list`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100)
        assets = { error("ESI is down") }

        val state = viewModel().state.value

        assertThat(state.failures.single().message).isEqualTo("ESI is down")
        assertThat(state.lines.single().status).isEqualTo(StockpileStatus.BelowRequired)
    }

    @Test
    fun `adding searches the types, and choosing one with levels saves it and updates the list`() {
        system.store(1, "Alice")
        assets = { listOf(asset(1, 100, 40)) }
        val viewModel = viewModel()

        viewModel.startAdd()
        viewModel.searchItems("multifreq")
        assertThat(viewModel.state.value.form?.suggestions).containsExactly(crystals)
        viewModel.chooseItem(crystals)
        viewModel.setRequired("100")
        viewModel.setPreferred("300")
        viewModel.save()

        assertThat(saved.values).containsExactly(Stockpile(100, "Multifrequency L", required = 100, preferred = 300))
        assertThat(viewModel.state.value.form).isNull()
        assertThat(viewModel.state.value.lines.map { it.status }).containsExactly(StockpileStatus.BelowRequired)
    }

    @Test
    fun `levels may be typed with thousands separators and the optional ones left blank`() {
        system.store(1, "Alice")
        val viewModel = viewModel()

        viewModel.startAdd()
        viewModel.chooseItem(crystals)
        viewModel.setRequired("5,000,000")
        viewModel.save()

        assertThat(saved.values).containsExactly(Stockpile(100, "Multifrequency L", required = 5_000_000))
    }

    @Test
    fun `invalid input shows an error, keeps the form open and saves nothing`() {
        system.store(1, "Alice")
        val viewModel = viewModel()
        viewModel.startAdd()

        viewModel.save()
        assertThat(viewModel.state.value.form?.error).isEqualTo("Choose an item")

        viewModel.chooseItem(crystals)
        viewModel.save()
        assertThat(viewModel.state.value.form?.error).isEqualTo("Required level must be a whole number")

        viewModel.setRequired("100")
        viewModel.setPreferred("many")
        viewModel.save()
        assertThat(viewModel.state.value.form?.error).isEqualTo("Preferred level must be a whole number")

        viewModel.setPreferred("50")
        viewModel.save()
        assertThat(viewModel.state.value.form?.error).isEqualTo("Levels must satisfy required <= preferred <= overflow")

        assertThat(saved).isEmpty()
        assertThat(viewModel.state.value.form).isNotNull()
    }

    @Test
    fun `editing prefills the form and saving replaces the levels`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100, preferred = 300, overflow = 1_000)
        val viewModel = viewModel()

        viewModel.edit(StockpileKey(100))
        val form = viewModel.state.value.form!!
        assertThat(form.item).isEqualTo(crystals)
        assertThat(listOf(form.required, form.preferred, form.overflow)).containsExactly("100", "300", "1000")
        viewModel.setPreferred("")
        viewModel.setOverflow("")
        viewModel.setRequired("150")
        viewModel.save()

        assertThat(saved.values).containsExactly(Stockpile(100, "Multifrequency L", required = 150))
    }

    @Test
    fun `removing deletes the stockpile and cancelling closes the form without saving`() {
        system.store(1, "Alice")
        saved[StockpileKey(100)] = Stockpile(100, "Multifrequency L", required = 100)
        val viewModel = viewModel()

        viewModel.startAdd()
        viewModel.chooseItem(crystals)
        viewModel.setRequired("1")
        viewModel.cancel()
        assertThat(viewModel.state.value.form).isNull()
        assertThat(saved[StockpileKey(100)]?.required).isEqualTo(100)

        viewModel.remove(StockpileKey(100))

        assertThat(saved).isEmpty()
        assertThat(viewModel.state.value.lines).isEmpty()
    }

    private val jita = AssetLocation(60000001, "Jita IV")
    private val raitaru = AssetLocation(1_000_000_000_001, "Raitaru")

    @Test
    fun `a stockpile at a station counts only that station and says where it is`() {
        system.store(1, "Alice")
        saved[StockpileKey(100, jita.id)] = Stockpile(100, "Multifrequency L", required = 100, station = jita)
        assets = {
            listOf(
                AssetItem(1, AssetType(100, null), null, 40, jita),
                AssetItem(2, AssetType(100, null), null, 900, raitaru),
            )
        }

        val line = viewModel().state.value.lines.single()

        assertThat(line.key).isEqualTo(StockpileKey(100, jita.id))
        assertThat(line.text).isEqualTo("Multifrequency L @ Jita IV  on hand 40  required 100")
        assertThat(line.status).isEqualTo(StockpileStatus.BelowRequired)
    }

    @Test
    fun `the form offers the stations the assets are in and saves the chosen one`() {
        system.store(1, "Alice")
        assets = { listOf(AssetItem(1, AssetType(100, null), null, 40, jita), AssetItem(2, AssetType(5, null), null, 1, raitaru)) }
        val viewModel = viewModel()

        assertThat(viewModel.state.value.stations).containsExactly(jita, raitaru)
        viewModel.startAdd()
        viewModel.chooseItem(crystals)
        viewModel.chooseStation(jita)
        viewModel.setRequired("100")
        viewModel.save()

        assertThat(saved.values).containsExactly(Stockpile(100, "Multifrequency L", required = 100, station = jita))
    }

    @Test
    fun `editing prefills the station, and moving a stockpile to another station replaces it`() {
        system.store(1, "Alice")
        saved[StockpileKey(100, jita.id)] = Stockpile(100, "Multifrequency L", required = 100, station = jita)
        val viewModel = viewModel()

        viewModel.edit(StockpileKey(100, jita.id))
        assertThat(viewModel.state.value.form?.station).isEqualTo(jita)
        viewModel.chooseStation(raitaru)
        viewModel.save()

        assertThat(saved.values).containsExactly(Stockpile(100, "Multifrequency L", required = 100, station = raitaru))
    }
}
