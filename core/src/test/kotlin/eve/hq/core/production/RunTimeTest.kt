package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RunTimeTest {
    @Test
    fun `without any modifier a job takes its base time per run`() {
        assertThat(TimeModifiers().seconds(baseSecondsPerRun = 75, runs = 10)).isEqualTo(750)
    }

    @Test
    fun `reductions multiply, they do not add up`() {
        // 20% off from the blueprint and 15% off from the structure is 0.8 x 0.85 = 0.68, not 0.65.
        val modifiers = TimeModifiers(blueprintTe = 20, structure = 0.15)

        assertThat(modifiers.seconds(100, 1)).isEqualTo(68)
    }

    @Test
    fun `skills and implants multiply with everything else`() {
        val modifiers = TimeModifiers(skills = listOf(0.20, 0.15), implants = listOf(0.02))

        assertThat(modifiers.seconds(10_000, 1)).isEqualTo(6_664) // 0.8 x 0.85 x 0.98
    }

    // Reproduces the game: 21,772 runs of Photon Microprocessor (75 s base) by a character with Industry V, Advanced Industry V
    // and Electronic Engineering IV (the blueprint requires it; 1% a level), one 2% implant (Zainou 'Beancounter' BX-802),
    // at a Raitaru (x0.85), using a 20% TE blueprint. The game shows 8d 5h 19m, and "-36%" for skills and implants.
    @Test
    fun `a real job from the game is reproduced to the minute`() {
        val modifiers = TimeModifiers(
            blueprintTe = 20,
            skills = ManufacturingSkills.reductions(industry = 5, advancedIndustry = 5) + 0.04,
            implants = listOf(0.02),
            structure = 0.15,
        )

        val seconds = modifiers.seconds(baseSecondsPerRun = 75, runs = 21_772)

        val inGame = Duration.ofDays(8).plusHours(5).plusMinutes(19).seconds
        assertThat(seconds).isBetween(inGame, inGame + 59) // the game shows whole minutes
        // Skills and implants alone: the game rounds this to -36%.
        assertThat(TimeModifiers(skills = modifiers.skills, implants = modifiers.implants).factor.toDouble()).isBetween(0.6395, 0.6400)
    }

    @Test
    fun `industry takes 4 percent and advanced industry 3 percent off per level`() {
        assertThat(ManufacturingSkills.reductions(industry = 3, advancedIndustry = 0)).containsExactly(0.12, 0.0)
        assertThat(ManufacturingSkills.reductions(industry = 0, advancedIndustry = 4)).containsExactly(0.0, 0.12)
    }

    @Test
    fun `the time is rounded once, not per run`() {
        // 75 s x 0.4352 = 32.64 s a run; 10 runs are 326.4 s, which rounds to 326, where per-run rounding would give 330.
        val modifiers = TimeModifiers(blueprintTe = 20, structure = 0.15, skills = listOf(0.36))

        assertThat(modifiers.seconds(75, 10)).isEqualTo(326)
    }

    @Test
    fun `reductions outside what the game allows are rejected`() {
        assertFailsWith<IllegalArgumentException> { TimeModifiers(blueprintTe = 22) }
        assertFailsWith<IllegalArgumentException> { TimeModifiers(structure = 1.0) }
        assertFailsWith<IllegalArgumentException> { TimeModifiers(skills = listOf(-0.1)) }
        assertFailsWith<IllegalArgumentException> { ManufacturingSkills.reductions(industry = 6, advancedIndustry = 0) }
    }

    @Test
    fun `a window holds as many whole runs as fit, and at least one`() {
        assertThat(TimeModifiers().maxRuns(baseSecondsPerRun = 100, within = Duration.ofSeconds(1_000))).isEqualTo(10)
        assertThat(TimeModifiers(structure = 0.5).maxRuns(100, Duration.ofSeconds(1_000))).isEqualTo(20) // runs take 50 s
        assertThat(TimeModifiers().maxRuns(150, Duration.ofSeconds(1_000))).isEqualTo(6) // 6.67 whole runs
        assertThat(TimeModifiers().maxRuns(5_000, Duration.ofSeconds(1_000))).isEqualTo(1) // a single run outlasts the window
    }
}
