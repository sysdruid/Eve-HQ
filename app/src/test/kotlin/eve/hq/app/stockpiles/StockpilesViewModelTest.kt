package eve.hq.app.stockpiles

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemType
import eve.hq.core.production.ListStockpiles
import eve.hq.core.production.Stockpile
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

    private val saved = linkedMapOf<TypeId, Stockpile>()
    private val repository = object : StockpileRepository {
        override fun all() = saved.values.toList()

        override fun save(stockpile: Stockpile) {
            saved[stockpile.item] = stockpile
        }

        override fun remove(item: TypeId) {
            saved.remove(item)
        }
    }
    private val crystals = ItemType(100, "Multifrequency L")
    private val catalog = TypeCatalog { query -> listOf(crystals).filter { it.name.contains(query, ignoreCase = true) } }
    private var assets: suspend () -> List<AssetItem> = { emptyList() }

    private fun viewModel() = StockpilesViewModel(
        ListCharacters(system.repository),
        ListStockpiles(repository, AssetSource { assets() }),
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
        saved[100] = Stockpile(100, "Multifrequency L", required = 100, preferred = 300)
        val pending = CompletableDeferred<List<AssetItem>>()
        assets = { pending.await() }

        val viewModel = viewModel()

        assertThat(viewModel.state.value.loading).isTrue()
        pending.complete(listOf(asset(1, 100, 1_210)))
        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.lines).containsExactly(
            StockpileLine(100, "Multifrequency L  on hand 1,210  required 100  preferred 300", StockpileStatus.Ok),
        )
    }

    @Test
    fun `a character that fails to load is reported while the stockpiles still list`() {
        system.store(1, "Alice")
        saved[100] = Stockpile(100, "Multifrequency L", required = 100)
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
        saved[100] = Stockpile(100, "Multifrequency L", required = 100, preferred = 300, overflow = 1_000)
        val viewModel = viewModel()

        viewModel.edit(100)
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
        saved[100] = Stockpile(100, "Multifrequency L", required = 100)
        val viewModel = viewModel()

        viewModel.startAdd()
        viewModel.chooseItem(crystals)
        viewModel.setRequired("1")
        viewModel.cancel()
        assertThat(viewModel.state.value.form).isNull()
        assertThat(saved[100]?.required).isEqualTo(100)

        viewModel.remove(100)

        assertThat(saved).isEmpty()
        assertThat(viewModel.state.value.lines).isEmpty()
    }
}
