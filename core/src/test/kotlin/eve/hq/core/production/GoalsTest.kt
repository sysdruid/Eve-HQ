package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
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
}
