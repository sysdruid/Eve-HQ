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
    fun `the anathema project requires every direct input of five hundred jobs`() {
        val project = Projects(Items(tree), anathemaRecipe).get(ProjectId.AnathemaInputs)

        assertThat(project.name).isEqualTo("Inputs for 500 Anathemas")
        assertThat(project.requirements()).containsExactlyInAnyOrder(
            Requirement(plate, 150_000, "500 Anathema jobs"),
            Requirement(magnate, 500, "500 Anathema jobs"),
            Requirement(tool, 1_500, "500 Anathema jobs"),
        )
    }

    @Test
    fun `the anathema project builds components, composites and intermediates, and buys the rest`() {
        val project = Projects(Items(tree), anathemaRecipe).get(ProjectId.AnathemaInputs)

        assertThat(listOf(plate, composite, intermediate).map(project::builds)).containsOnly(true)
        assertThat(listOf(magnate, tool, fuelBlock).map(project::builds)).containsOnly(false)
    }

    private val zealot = 11000L
    private val sacrilege = 11001L
    private val datacore = 20000L

    private val hullNames = FakeItemCatalog(
        names = mapOf(zealot to "Zealot", sacrilege to "Sacrilege", plate to "Plate", datacore to "Datacore"),
        groups = mapOf(plate to "Construction Components", datacore to "Datacore"),
    )
    private val hullRecipes = RecipeBook { product ->
        when (product) {
            zealot -> Recipe(zealot, 1, mapOf(plate to 300), blueprint = 1, secondsPerRun = 240_000)
            sacrilege -> Recipe(sacrilege, 1, mapOf(plate to 200, datacore to 5), blueprint = 2, secondsPerRun = 300_000)
            else -> null
        }
    }
    private val config = ReadinessConfig(
        hulls = listOf("Zealot", "Sacrilege"),
        cycle = CycleSettings(java.time.Duration.ofDays(14), window = java.time.Duration.ofHours(12)),
        jobs = 10,
    )

    @Test
    fun `the medium hull project is ready for jobs of any listed hull in the configured cycle`() {
        val project = Projects(Items(hullNames), hullRecipes, config).get(ProjectId.MediumT2Hulls)

        // 14 days less 12 hours: 4 runs of the 240,000 s hull, 3 of the 300,000 s one. Plate: largest of 300 x 4 and 200 x 3 = 1200;
        // datacore 5 x 3 = 15; both x 10 jobs.
        assertThat(project.requirements().associate { it.item to it.required })
            .containsExactlyInAnyOrderEntriesOf(mapOf(plate to 12_000L, datacore to 150L))
        assertThat(project.cycle).isEqualTo(config.cycle)
        assertThat(project.builds(plate)).isTrue()
        assertThat(project.builds(datacore)).isFalse()
    }

    @Test
    fun `a hull missing from the catalog fails when the project is defined`() {
        val misspelt = config.copy(hulls = listOf("Zealot", "Zealott"))

        assertFailsWith<IllegalArgumentException> { Projects(Items(hullNames), hullRecipes, misspelt).get(ProjectId.MediumT2Hulls) }
    }

    private val legion = 29986L
    private val tengu = 29984L
    private val nanowire = 21001L
    private val radarAmp = 21002L
    private val gravAmp = 21003L
    private val ram = 3828L
    private val polymer = 21100L

    private val t3Catalog = FakeItemCatalog(
        names = mapOf(legion to "Legion", tengu to "Tengu", nanowire to "Nanowire", radarAmp to "Radar Amp", gravAmp to "Grav Amp", ram to "RAM", polymer to "Polymer", fuelBlock to "Fuel Block"),
        groups = mapOf(nanowire to "Hybrid Tech Components", radarAmp to "Hybrid Tech Components", gravAmp to "Hybrid Tech Components", ram to "Tool", polymer to "Hybrid Polymers", fuelBlock to "Fuel Block"),
    )
    private val t3Recipes = RecipeBook { product ->
        when (product) {
            legion -> Recipe(legion, 1, mapOf(nanowire to 21, radarAmp to 12, ram to 15), blueprint = 1, secondsPerRun = 300_000)
            tengu -> Recipe(tengu, 1, mapOf(nanowire to 21, gravAmp to 12, ram to 15), blueprint = 2, secondsPerRun = 300_000)
            else -> null
        }
    }
    private val t3Config = FixedRunConfig(
        hulls = listOf("Legion", "Tengu"),
        hullCount = 14, // not a whole number of 4-run copies: rounds up to 16
        copyRuns = 4,
        cycle = CycleSettings(java.time.Duration.ofDays(14), window = java.time.Duration.ofHours(12)),
    )

    @Test
    fun `the T3 project is ready for whole copies of either hull, taking the largest need of each material`() {
        val project = Projects(Items(t3Catalog), t3Recipes, t3Cruisers = t3Config).get(ProjectId.T3Cruisers)

        assertThat(t3Config.runs).isEqualTo(16)
        assertThat(project.requirements().associate { it.item to it.required }).containsExactlyInAnyOrderEntriesOf(
            mapOf(nanowire to 336L, radarAmp to 192L, gravAmp to 192L, ram to 240L),
        )
        assertThat(project.cycle).isEqualTo(t3Config.cycle)
    }

    @Test
    fun `the T3 project builds the hybrid components and polymers and buys the tool and the fuel`() {
        val project = Projects(Items(t3Catalog), t3Recipes, t3Cruisers = t3Config).get(ProjectId.T3Cruisers)

        assertThat(listOf(nanowire, radarAmp, polymer).map(project::builds)).containsOnly(true)
        assertThat(listOf(ram, fuelBlock).map(project::builds)).containsOnly(false)
    }

    @Test
    fun `each project knows the items it exists to produce`() {
        val projects = Projects(Items(t3Catalog + hullNames + catalog + tree), allRecipes, config, t3Config)

        assertThat(projects.get(ProjectId.AmarrShuttles).endProducts).containsExactly(11134L)
        assertThat(projects.get(ProjectId.MediumT2Hulls).endProducts).containsExactlyInAnyOrder(zealot, sacrilege)
        assertThat(projects.get(ProjectId.T3Cruisers).endProducts).containsExactlyInAnyOrder(legion, tengu)
        assertThat(projects.get(ProjectId.AnathemaInputs).endProducts).containsExactly(anathema)
    }

    private val allRecipes = RecipeBook { product -> listOf(hullRecipes, t3Recipes, anathemaRecipe).firstNotNullOfOrNull { it.forProduct(product) } }
}
