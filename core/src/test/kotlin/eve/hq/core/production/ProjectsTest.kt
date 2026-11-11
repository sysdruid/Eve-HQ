package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProjectsTest {
    private val catalog = FakeItemCatalog(names = mapOf(11134L to "Amarr Shuttle", 34L to "Tritanium"))

    @Test
    fun `the shuttle project requires a thousand shuttles`() {
        val project = Projects(Items(catalog), RecipeBook { null }).get(ProjectId.AmarrShuttles)

        assertThat(project.name).isEqualTo("1000 Amarr Shuttles")
        assertThat(project.requirements()).containsExactly(Requirement(11134, 1_000, "build 1000 shuttles"))
    }

    @Test
    fun `a project whose item is missing from the catalog fails when it is defined`() {
        val withoutShuttle = Projects(Items(FakeItemCatalog(names = mapOf(34L to "Tritanium"))), RecipeBook { null })

        assertFailsWith<IllegalArgumentException> { withoutShuttle.get(ProjectId.AmarrShuttles) }
    }

    private val anathema = 11188L
    private val plate = 11543L
    private val composite = 16672L
    private val intermediate = 16657L
    private val magnate = 11183L
    private val tool = 3828L
    private val fuelBlock = 4247L

    private val tree = FakeItemCatalog(
        names = mapOf(
            anathema to "Anathema", plate to "Tungsten Carbide Armor Plate", composite to "Tungsten Carbide",
            intermediate to "Rolled Tungsten Alloy", magnate to "Magnate", tool to "R.A.M.- Starship Tech",
            fuelBlock to "Helium Fuel Block",
        ),
        groups = mapOf(
            anathema to "Covert Ops", plate to "Construction Components", composite to "Composite",
            intermediate to "Intermediate Materials", magnate to "Frigate", tool to "Tool", fuelBlock to "Fuel Block",
        ),
    )
    private val anathemaRecipe = RecipeBook { product ->
        Recipe(anathema, 1, mapOf(plate to 300, magnate to 1, tool to 3), blueprint = 11189, secondsPerRun = 120_000)
            .takeIf { product == anathema }
    }

    @Test
    fun `the anathema project requires every direct input of ten jobs`() {
        val project = Projects(Items(tree), anathemaRecipe).get(ProjectId.AnathemaInputs)

        assertThat(project.name).isEqualTo("Inputs for 10 Anathemas")
        assertThat(project.requirements()).containsExactlyInAnyOrder(
            Requirement(plate, 3_000, "10 Anathema jobs"),
            Requirement(magnate, 10, "10 Anathema jobs"),
            Requirement(tool, 30, "10 Anathema jobs"),
        )
    }

    @Test
    fun `the anathema project builds components, composites and intermediates, and buys the rest`() {
        val project = Projects(Items(tree), anathemaRecipe).get(ProjectId.AnathemaInputs)

        assertThat(listOf(plate, composite, intermediate).map(project::builds)).containsOnly(true)
        assertThat(listOf(magnate, tool, fuelBlock).map(project::builds)).containsOnly(false)
    }
}
