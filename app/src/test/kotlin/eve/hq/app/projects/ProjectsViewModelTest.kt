package eve.hq.app.projects

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.character.ListCharacters
import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.Items
import eve.hq.core.production.PlanProject
import eve.hq.core.production.ProjectId
import eve.hq.core.production.Projects
import eve.hq.core.production.Recipe
import eve.hq.core.production.RecipeBook
import eve.hq.core.production.StockpileStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)

    private val names = mutableMapOf(11134L to "Amarr Shuttle", 11135L to "Amarr Shuttle Blueprint", 34L to "Tritanium")
    private val catalog = object : ItemCatalog {
        override fun itemName(item: Long) = names[item]

        override fun groupName(item: Long): String? = null

        override fun findItem(name: String) = names.entries.firstOrNull { it.value == name }?.key
    }
    private val recipes = RecipeBook { product ->
        Recipe(11134, 1, mapOf(34L to 2778), blueprint = 11135, secondsPerRun = 6000).takeIf { product == 11134L }
    }
    private var assets: suspend () -> List<AssetItem> = { emptyList() }
    private val service = AssetService(AssetSource { assets() }, Clock.systemUTC())

    private fun viewModel() = ProjectsViewModel(
        ListCharacters(system.repository),
        PlanProject(Projects(Items(catalog)), recipes, service),
        catalog,
        scope,
        dispatcher,
    )

    private fun tritanium(quantity: Int) =
        AssetItem(1, AssetType(34, "Tritanium"), null, quantity, AssetLocation(60000001, "Jita IV"))

    @Test
    fun `loading is visible until the assets arrive, then goals, phases and the shopping list are shown`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<AssetItem>>()
        assets = { pending.await() }

        val viewModel = viewModel()

        assertThat(viewModel.state.value.loading).isTrue()
        pending.complete(listOf(tritanium(1_000)))
        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.title).isEqualTo("10 Amarr Shuttles")
        assertThat(state.goals).containsExactly("10 × Amarr Shuttle")
        assertThat(state.phases).containsExactly(
            PhaseBlock("Phase 1", listOf("Amarr Shuttle Blueprint  10 runs × 1h 40m  → Amarr Shuttle")),
        )
        assertThat(state.shopping).containsExactly(
            ShoppingLine("Tritanium  need 27,780  on hand 1,000  short 26,780", StockpileStatus.BelowRequired),
        )
    }

    @Test
    fun `a character whose assets fail is reported`() {
        system.store(1, "Alice")
        assets = { error("ESI is down") }

        val state = viewModel().state.value

        assertThat(state.failures.single().message).isEqualTo("ESI is down")
        assertThat(state.shopping.single().text).contains("on hand 0")
    }

    @Test
    fun `a project whose items are not in the imported data shows why instead of failing`() {
        system.store(1, "Alice")
        names.remove(11134L)

        val state = viewModel().state.value

        assertThat(state.loading).isFalse()
        assertThat(state.error).contains("Amarr Shuttle")
        assertThat(state.phases).isEmpty()
    }
}
