package eve.hq.app.levels

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class LevelsTest {
    @Test
    fun `stock that meets the need is covered, even with nothing needed`() {
        assertThat(levelOf(onHand = 100, required = 100)).isEqualTo(Level.Covered)
        assertThat(levelOf(onHand = 5_000, required = 100)).isEqualTo(Level.Covered)
        assertThat(levelOf(onHand = 0, required = 0)).isEqualTo(Level.Covered)
    }

    @Test
    fun `from 85 percent up to the need is close`() {
        assertThat(levelOf(99, 100)).isEqualTo(Level.Close)
        assertThat(levelOf(85, 100)).isEqualTo(Level.Close) // exactly 85% still counts
        assertThat(levelOf(84, 100)).isEqualTo(Level.Low)
    }

    @Test
    fun `from half up to 85 percent is low, below half is short`() {
        assertThat(levelOf(50, 100)).isEqualTo(Level.Low) // exactly half still counts
        assertThat(levelOf(49, 100)).isEqualTo(Level.Short)
        assertThat(levelOf(0, 100)).isEqualTo(Level.Short)
    }

    @Test
    fun `the thresholds hold for large quantities without rounding errors`() {
        assertThat(levelOf(2_361_300, 2_778_000)).isEqualTo(Level.Close) // exactly 85%
        assertThat(levelOf(2_361_299, 2_778_000)).isEqualTo(Level.Low)
        assertThat(levelOf(1_389_000, 2_778_000)).isEqualTo(Level.Low) // exactly half
        assertThat(levelOf(1_388_999, 2_778_000)).isEqualTo(Level.Short)
    }

    @Test
    fun `numbers under a thousand are shown exactly, larger ones with three significant digits`() {
        assertThat(compact(0)).isEqualTo("0")
        assertThat(compact(999)).isEqualTo("999")
        assertThat(compact(1_000)).isEqualTo("1k")
        assertThat(compact(1_230)).isEqualTo("1.23k")
        assertThat(compact(12_345)).isEqualTo("12.3k")
        assertThat(compact(123_456)).isEqualTo("123k")
        assertThat(compact(2_778_000)).isEqualTo("2.78M")
        assertThat(compact(1_500_000_000)).isEqualTo("1.5B")
    }

    @Test
    fun `rounding up into the next unit does not leave 1000k`() {
        assertThat(compact(999_999)).isEqualTo("1M")
        assertThat(compact(999_500)).isEqualTo("1M")
        assertThat(compact(999_400)).isEqualTo("999k")
    }
}
