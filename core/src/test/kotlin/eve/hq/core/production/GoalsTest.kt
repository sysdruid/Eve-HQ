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
    fun `speed multiplies the quantities and a fraction is rounded up`() {
        val book = hullRecipes(hull(1, 240_000, tritanium to 3, pyerite to 1))

        val required = readinessRequirements(book, listOf(1), CycleSettings(twoWeeks, speed = 1.4), jobs = 1, because = "ready")

        // 3 x 5 runs x 1.4 = 21 exactly, 1 x 5 x 1.4 = 7 exactly: no spurious round-up from floating point.
        assertThat(required).containsExactlyInAnyOrder(Requirement(tritanium, 21, "ready"), Requirement(pyerite, 7, "ready"))
        val odd = hullRecipes(hull(1, 240_000, tritanium to 1))
        assertThat(readinessRequirements(odd, listOf(1), CycleSettings(twoWeeks, speed = 1.5), 1, "ready").single().required)
            .isEqualTo(8) // 5 x 1.5 = 7.5
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
    fun `a cycle must last and speed must be positive`() {
        assertFailsWith<IllegalArgumentException> { CycleSettings(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { CycleSettings(twoWeeks, speed = 0.0) }
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
}
