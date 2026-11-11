package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlanProductionTest {
    private val tritanium = 34L
    private val pyerite = 35L
    private val rifter = 587L

    private val recipes = mutableMapOf<TypeId, Recipe>()

    private fun recipe(product: TypeId, perRun: Int = 1, vararg materials: Pair<TypeId, Int>) {
        recipes[product] = Recipe(product, perRun, materials.toMap())
    }

    private fun project(name: String, vararg required: Pair<TypeId, Long>, stop: Set<TypeId> = emptySet()) =
        FixedRequirementsProject(name, required.map { (item, quantity) -> Requirement(item, quantity, "test") }, stop)

    private fun plan(
        vararg projects: Project,
        manual: List<Stockpile> = emptyList(),
        stock: Map<TypeId, Long> = emptyMap(),
    ) = PlanProduction({ recipes[it] })(projects.toList(), manual, stock)

    @Test
    fun `a requirement with a recipe is one job and its materials are shopped`() {
        recipe(rifter, 1, tritanium to 100, pyerite to 20)

        val plan = plan(project("Frigates", rifter to 3))

        assertThat(plan.jobs).containsExactly(PlannedJob(rifter, 3, setOf("Frigates")))
        assertThat(plan.shoppingList.associate { it.item to it.deficit })
            .containsExactlyInAnyOrderEntriesOf(mapOf(tritanium to 300L, pyerite to 60L))
    }

    private val component = 11000L
    private val merlin = 603L

    private fun shopped(plan: Plan) = plan.shoppingList.associate { it.item to it.deficit }

    private fun chainRecipes() {
        recipe(rifter, 1, component to 5, pyerite to 20)
        recipe(merlin, 1, component to 3)
        recipe(component, 1, tritanium to 8)
    }

    @Test
    fun `runs round up to whole runs of the product quantity`() {
        recipe(rifter, 4, tritanium to 10)

        val plan = plan(project("Frigates", rifter to 10))

        assertThat(plan.jobs).containsExactly(PlannedJob(rifter, 3, setOf("Frigates")))
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(tritanium to 30L))
    }

    @Test
    fun `an intermediate is a job and only the leaves are shopped`() {
        chainRecipes()

        val plan = plan(project("Frigates", rifter to 2))

        assertThat(plan.jobs.map { it.product to it.runs }).containsExactlyInAnyOrder(rifter to 2L, component to 10L)
        assertThat(shopped(plan)).containsExactlyInAnyOrderEntriesOf(mapOf(tritanium to 80L, pyerite to 40L))
    }

    @Test
    fun `a requirement for an item without a recipe is shopped`() {
        val plan = plan(project("Mining", tritanium to 500))

        assertThat(plan.jobs).isEmpty()
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(tritanium to 500L))
    }

    @Test
    fun `recipes that form a cycle fail loudly`() {
        recipe(rifter, 1, component to 1)
        recipe(component, 1, rifter to 1)

        assertFailsWith<IllegalStateException> { plan(project("Loop", rifter to 1)) }
    }

    @Test
    fun `stock that covers the requirement needs no job and no purchase`() {
        recipe(rifter, 1, tritanium to 100)

        val plan = plan(project("Frigates", rifter to 3), stock = mapOf(rifter to 3L))

        assertThat(plan.jobs).isEmpty()
        assertThat(plan.shoppingList).isEmpty()
        assertThat(plan.demands.single().deficit).isZero()
    }

    @Test
    fun `partial stock shrinks the job to the deficit`() {
        recipe(rifter, 1, tritanium to 100)

        val plan = plan(project("Frigates", rifter to 3), stock = mapOf(rifter to 1L))

        assertThat(plan.jobs).containsExactly(PlannedJob(rifter, 2, setOf("Frigates")))
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(tritanium to 200L))
    }

    @Test
    fun `stock of an intermediate shrinks its job and the materials below it`() {
        chainRecipes()

        val plan = plan(project("Frigates", rifter to 2), stock = mapOf(component to 4L))

        assertThat(plan.jobs.single { it.product == component }.runs).isEqualTo(6)
        assertThat(shopped(plan)[tritanium]).isEqualTo(48)
    }

    @Test
    fun `two projects needing one intermediate add their demand and share one job`() {
        chainRecipes()

        val plan = plan(project("Rifters", rifter to 1), project("Merlins", merlin to 1))

        assertThat(plan.jobs.filter { it.product == component })
            .containsExactly(PlannedJob(component, 8, setOf("Rifters", "Merlins")))
    }

    @Test
    fun `two projects needing one leaf count the stock once`() {
        val plan = plan(project("A", tritanium to 700), project("B", tritanium to 800), stock = mapOf(tritanium to 1000L))

        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(tritanium to 500L))
    }

    @Test
    fun `each item lists who asked for how much`() {
        chainRecipes()

        val plan = plan(project("Rifters", rifter to 1), project("Merlins", merlin to 1))

        fun contributions(item: TypeId) = plan.demands.single { it.item == item }.contributions
        assertThat(contributions(rifter))
            .containsExactly(Contribution(ContributionSource.ProjectGoal("Rifters", "test"), 1))
        assertThat(contributions(component)).containsExactlyInAnyOrder(
            Contribution(ContributionSource.Jobs(rifter), 5),
            Contribution(ContributionSource.Jobs(merlin), 3),
        )
        assertThat(contributions(tritanium)).containsExactly(Contribution(ContributionSource.Jobs(component), 64))
    }

    @Test
    fun `a manual stockpile adds to what the projects need`() {
        recipe(rifter, 1, tritanium to 1_500)
        val manual = listOf(Stockpile(tritanium, "Tritanium", required = 2_000))

        val plan = plan(project("Frigates", rifter to 1), manual = manual, stock = mapOf(tritanium to 1_400L))

        val demand = plan.demands.single { it.item == tritanium }
        assertThat(demand.required).isEqualTo(3_500)
        assertThat(demand.deficit).isEqualTo(2_100)
        assertThat(demand.contributions.map { it.source })
            .containsExactlyInAnyOrder(ContributionSource.ManualStockpile, ContributionSource.Jobs(rifter))
    }

    @Test
    fun `a manual stockpile alone builds nothing even when the item has a recipe`() {
        recipe(rifter, 1, tritanium to 100)

        val plan = plan(manual = listOf(Stockpile(rifter, "Rifter", required = 5)))

        assertThat(plan.jobs).isEmpty()
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(rifter to 5L))
    }

    @Test
    fun `a stop item is bought, not expanded`() {
        chainRecipes()

        val plan = plan(project("Frigates", rifter to 2, stop = setOf(component)))

        assertThat(plan.jobs.map { it.product }).containsExactly(rifter)
        assertThat(shopped(plan)).containsExactlyInAnyOrderEntriesOf(mapOf(component to 10L, pyerite to 40L))
    }

    @Test
    fun `a requirement that is itself a stop item is bought`() {
        recipe(rifter, 1, tritanium to 100)

        val plan = plan(project("Frigates", rifter to 2, stop = setOf(rifter)))

        assertThat(plan.jobs).isEmpty()
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(rifter to 2L))
    }

    @Test
    fun `an item one project stops at is built when another project builds it, for everyone's demand`() {
        chainRecipes()

        val plan = plan(
            project("Rifters", rifter to 1, stop = setOf(component)),
            project("Merlins", merlin to 1),
        )

        assertThat(plan.jobs.single { it.product == component })
            .isEqualTo(PlannedJob(component, 8, setOf("Merlins")))
        assertThat(shopped(plan)).containsExactlyInAnyOrderEntriesOf(mapOf(tritanium to 64L, pyerite to 20L))
    }

    private fun goal(item: TypeId, required: Long, preferred: Long) =
        FixedRequirementsProject("Goals", listOf(Requirement(item, required, "test", preferred)))

    @Test
    fun `an item obtained elsewhere stays on the shopping list until its preferred level is reached`() {
        val belowPreferred = plan(goal(tritanium, required = 100, preferred = 300), stock = mapOf(tritanium to 150L))
        val atPreferred = plan(goal(tritanium, required = 100, preferred = 300), stock = mapOf(tritanium to 300L))

        val demand = belowPreferred.shoppingList.single()
        assertThat(demand.deficit).isZero()
        assertThat(demand.deficitToPreferred).isEqualTo(150)
        assertThat(demand.status).isEqualTo(StockpileStatus.BelowPreferred)
        assertThat(atPreferred.shoppingList).isEmpty()
    }

    @Test
    fun `below the required level is reported as such, ahead of preferred`() {
        val plan = plan(goal(tritanium, required = 100, preferred = 300), stock = mapOf(tritanium to 40L))

        assertThat(plan.shoppingList.single().status).isEqualTo(StockpileStatus.BelowRequired)
        assertThat(plan.shoppingList.single().deficitToPreferred).isEqualTo(260)
    }

    @Test
    fun `a preferred level never creates jobs`() {
        recipe(rifter, 1, tritanium to 100)

        val plan = plan(goal(rifter, required = 2, preferred = 10))

        assertThat(plan.jobs).containsExactly(PlannedJob(rifter, 2, setOf("Goals")))
        assertThat(shopped(plan)).containsExactlyEntriesOf(mapOf(tritanium to 200L))
    }

    @Test
    fun `preferred levels add up like required ones, and job demand asks for no more than it needs`() {
        recipe(rifter, 1, tritanium to 1_500)
        val manual = listOf(Stockpile(tritanium, "Tritanium", required = 2_000, preferred = 3_000))

        val plan = plan(project("Frigates", rifter to 1), manual = manual, stock = mapOf(tritanium to 4_000L))

        val demand = plan.demands.single { it.item == tritanium }
        assertThat(demand.required).isEqualTo(3_500)
        assertThat(demand.preferred).isEqualTo(4_500)
        assertThat(demand.status).isEqualTo(StockpileStatus.BelowPreferred)
        assertThat(plan.shoppingList.map { it.item }).containsExactly(tritanium)
    }

    @Test
    fun `a preferred level below the required one is rejected`() {
        assertFailsWith<IllegalArgumentException> { Requirement(tritanium, 100, "test", preferred = 99) }
    }
}
