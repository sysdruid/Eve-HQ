package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GoalsTest {
    private val recipes = RecipeBook { product ->
        Recipe(100, 1, mapOf(1L to 300, 2L to 1), blueprint = 101, secondsPerRun = 60).takeIf { product == 100L }
    }

    @Test
    fun `the inputs of several jobs are every direct input times the number of jobs`() {
        assertThat(inputsOfJobs(recipes, 100, 10, "ten jobs")).containsExactlyInAnyOrder(
            Requirement(1, 3_000, "ten jobs"),
            Requirement(2, 10, "ten jobs"),
        )
    }

    @Test
    fun `an item nothing can build has no inputs to ask for`() {
        assertFailsWith<IllegalArgumentException> { inputsOfJobs(recipes, 999, 10, "ten jobs") }
    }

    private val twoWeeks = Duration.ofDays(14)
    private val tritanium = 34L
    private val pyerite = 35L
    private val datacore = 99L

    private fun hullRecipes(vararg hulls: Recipe) = RecipeBook { product -> hulls.firstOrNull { it.product == product } }

    private fun hull(id: Long, secondsPerRun: Int, vararg materials: Pair<Long, Int>) =
        Recipe(id, 1, materials.toMap(), blueprint = id + 1_000, secondsPerRun = secondsPerRun)

    @Test
    fun `readiness asks for the inputs of the runs that fit in a cycle, times the jobs`() {
        // 14 days is 1,209,600 s; at 240,000 s a run, 5 runs fit.
        val book = hullRecipes(hull(1, 240_000, tritanium to 100, pyerite to 10))

        val required = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), jobs = 2, because = "ready")

        assertThat(required).containsExactlyInAnyOrder(
            Requirement(tritanium, 1_000, "ready"),
            Requirement(pyerite, 100, "ready"),
        )
    }

    @Test
    fun `with several hulls each material takes the largest need of any one hull, not their sum or average`() {
        val book = hullRecipes(
            hull(1, 240_000, tritanium to 100), // 5 runs: 500
            hull(2, 300_000, tritanium to 100, datacore to 3), // 4 runs: 400 and 12
        )

        val required = readinessRequirements(book, listOf(1, 2), CycleSettings(twoWeeks), jobs = 1, because = "ready")

        assertThat(required).containsExactlyInAnyOrder(
            Requirement(tritanium, 500, "ready"),
            Requirement(datacore, 12, "ready"),
        )
    }

    @Test
    fun `faster jobs fit more runs in the cycle, so more is asked for`() {
        // 240,000 s a run: 5 runs fit in 14 days. At half the time per run, 10 fit.
        val book = hullRecipes(hull(1, 240_000, tritanium to 3))

        val slow = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), jobs = 1, because = "ready")
        val fast = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), jobs = 1, because = "ready", runTimes = runTimesFasterBy(0.5))

        assertThat(slow).containsExactly(Requirement(tritanium, 15, "ready"))
        assertThat(fast).containsExactly(Requirement(tritanium, 30, "ready"))
    }

    @Test
    fun `a hull whose single run outlasts the cycle still counts one run`() {
        val book = hullRecipes(hull(1, 2_000_000, tritanium to 100))

        val required = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), jobs = 1, because = "ready")

        assertThat(required).containsExactly(Requirement(tritanium, 100, "ready"))
    }

    @Test
    fun `a hull nothing can build fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            readinessRequirements(hullRecipes(), listOf(1), CycleSettings(twoWeeks), 1, "ready")
        }
    }

    @Test
    fun `a cycle must last longer than zero`() {
        assertFailsWith<IllegalArgumentException> { CycleSettings(Duration.ZERO) }
    }

    @Test
    fun `the window keeps the end of the cycle free, so fewer runs fit`() {
        // 14 days less 12 hours is 1,166,400 s: at 240,000 s a run, 4 runs fit, not 5.
        val book = hullRecipes(hull(1, 240_000, tritanium to 100))
        val cycle = CycleSettings(twoWeeks, window = Duration.ofHours(12))

        assertThat(cycle.usable).isEqualTo(Duration.ofHours(14 * 24 - 12))
        assertThat(readinessRequirements(book, listOf(1), cycle, 1, "ready")).containsExactly(Requirement(tritanium, 400, "ready"))
    }

    @Test
    fun `the window must be shorter than the cycle and not negative`() {
        assertFailsWith<IllegalArgumentException> { CycleSettings(twoWeeks, window = twoWeeks) }
        assertFailsWith<IllegalArgumentException> { CycleSettings(twoWeeks, window = Duration.ofHours(-1)) }
    }

    @Test
    fun `a fixed run count asks for the largest single-hull need, with no speed and no cycle`() {
        val book = hullRecipes(
            hull(1, 300_000, tritanium to 21, pyerite to 12),
            hull(2, 300_000, tritanium to 21, datacore to 12),
        )

        val required = fixedRunRequirements(book, listOf(1, 2), runs = 16, because = "sixteen")

        assertThat(required).containsExactlyInAnyOrder(
            Requirement(tritanium, 336, "sixteen"),
            Requirement(pyerite, 192, "sixteen"),
            Requirement(datacore, 192, "sixteen"),
        )
    }

    @Test
    fun `the hulls are planned at the time efficiency of the copies that will be invented, not the one owned`() {
        // 240,000 s a run, 14 days. TE 20 would make a run 192,000 s (6 fit); an invented copy at TE 4 makes it 230,400 s (5 fit).
        val book = hullRecipes(hull(1, 240_000, tritanium to 10))
        val noRules = object : TimeRules {
            override fun bonus(type: TypeId, attribute: Int): Double? = null

            override fun requiredSkills(blueprint: TypeId, activity: eve.hq.core.industry.IndustryActivity) = emptyMap<TypeId, Int>()
        }
        val ownsTe20 = RunTimes(noRules, emptyList(), blueprintTe = { 20 })

        val atOwned = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), 1, "ready", ownsTe20)
        val invented = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks), 1, "ready", ownsTe20, blueprintTe = 4)

        assertThat(atOwned.single().required).isEqualTo(60)
        assertThat(invented.single().required).isEqualTo(50)
    }
}
