package eve.hq.app.projects

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.character.ListCharacters
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import eve.hq.core.industry.ListJobs
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

    private val names = mutableMapOf(
        11134L to "Amarr Shuttle", 11135L to "Amarr Shuttle Blueprint", 34L to "Tritanium",
        11188L to "Anathema", 11189L to "Anathema Blueprint", 11543L to "Tungsten Carbide Armor Plate",
    )
    private val catalog = object : ItemCatalog {
        override fun itemName(item: Long) = names[item]

        override fun groupName(item: Long): String? = null

        override fun findItem(name: String) = names.entries.firstOrNull { it.value == name }?.key
    }
    private val recipes = RecipeBook { product ->
        when (product) {
            11134L -> Recipe(11134, 1, mapOf(34L to 2778), blueprint = 11135, secondsPerRun = 6000)
            11188L -> Recipe(11188, 1, mapOf(11543L to 300), blueprint = 11189, secondsPerRun = 120_000)
            else -> null
        }
    }
    private var assets: suspend () -> List<AssetItem> = { emptyList() }
    private val service = AssetService(AssetSource { assets() }, Clock.systemUTC())

    private val now = java.time.Instant.parse("2026-11-11T12:00:00Z")
    private val clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
    private var running: List<IndustryJob> = emptyList()
    private var jobsFail = false
    private val jobSource = object : JobSource {
        override suspend fun load(character: eve.hq.core.character.EveCharacter) =
            if (jobsFail) error("ESI jobs are down") else running

        override fun cached(character: eve.hq.core.character.EveCharacter): List<IndustryJob>? = null
    }

    private fun job(id: Long, product: Long, name: String, runs: Int) = IndustryJob(
        jobId = id, activity = IndustryActivity.Manufacturing, blueprintId = id, blueprint = JobItem(id, "$name Blueprint"),
        product = JobItem(product, name), runs = runs, startedAt = now.minusSeconds(3600), endsAt = now.plusSeconds(3600),
        status = JobStatus.Active, facility = JobFacility(1, "Raitaru"),
    )

    private fun viewModel(): ProjectsViewModel {
        val projects = Projects(Items(catalog), recipes)
        return ProjectsViewModel(
            ListCharacters(system.repository),
            PlanProject(projects, recipes, service, ListJobs(jobSource, clock), clock),
            catalog,
            clock,
            scope,
            dispatcher,
        )
    }

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
        assertThat(state.title).isEqualTo("1000 Amarr Shuttles")
        assertThat(state.goals).containsExactly("1,000 × Amarr Shuttle")
        assertThat(state.phases).containsExactly(
            PhaseBlock("Phase 1", listOf("Amarr Shuttle Blueprint  1,000 runs × 1h 40m = 69d 10h  → Amarr Shuttle")),
        )
        assertThat(state.shopping).containsExactly(
            ShoppingLine("Tritanium  need 2,778,000  on hand 1,000  short 2,777,000", StockpileStatus.BelowRequired),
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

    @Test
    fun `the projects on offer are listed and selecting one plans that project`() {
        system.store(1, "Alice")
        val viewModel = viewModel()

        assertThat(viewModel.state.value.projects.map { it.id }).containsExactly(ProjectId.AmarrShuttles, ProjectId.AnathemaInputs, ProjectId.MediumT2Hulls, ProjectId.T3Cruisers)
        assertThat(viewModel.state.value.selected).isEqualTo(ProjectId.AmarrShuttles)

        viewModel.select(ProjectId.AnathemaInputs)

        val state = viewModel.state.value
        assertThat(state.selected).isEqualTo(ProjectId.AnathemaInputs)
        assertThat(state.title).isEqualTo("Inputs for 500 Anathemas")
        assertThat(state.goals).containsExactly("150,000 × Tungsten Carbide Armor Plate")
        assertThat(state.loading).isFalse()
    }

    @Test
    fun `selecting while another project is still planning switches to the newly selected one`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<AssetItem>>()
        assets = { pending.await() }
        val viewModel = viewModel()

        viewModel.select(ProjectId.AnathemaInputs)
        pending.complete(emptyList())

        assertThat(viewModel.state.value.title).isEqualTo("Inputs for 500 Anathemas")
    }

    @Test
    fun `running jobs that make the project's end product are listed with a summary`() {
        system.store(1, "Alice")
        running = listOf(job(1, 11134, "Amarr Shuttle", 10), job(2, 11134, "Amarr Shuttle", 5), job(3, 587, "Rifter", 20))

        val state = viewModel().state.value

        assertThat(state.hullJobs).hasSize(2)
        assertThat(state.hullJobs.first().text).startsWith("Alice-Amarr Shuttle [")
        assertThat(state.hullJobsSummary).isEqualTo("Amarr Shuttle 15 in progress (2 jobs)")
    }

    @Test
    fun `no running job for the project gives an empty list and no summary`() {
        system.store(1, "Alice")
        running = listOf(job(3, 587, "Rifter", 20))

        val state = viewModel().state.value

        assertThat(state.hullJobs).isEmpty()
        assertThat(state.hullJobsSummary).isEmpty()
    }

    @Test
    fun `a job load that fails is reported without hiding the plan`() {
        system.store(1, "Alice")
        jobsFail = true

        val state = viewModel().state.value

        assertThat(state.jobFailures.single().message).isEqualTo("ESI jobs are down")
        assertThat(state.phases).isNotEmpty()
    }
}
