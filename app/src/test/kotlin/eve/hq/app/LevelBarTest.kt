package eve.hq.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.skia.Bitmap
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class LevelBarTest {
    private fun colors(onHand: Long, required: Long, preferred: Long? = null, overflow: Long? = null): (Float) -> Int {
        val scene = ImageComposeScene(WIDTH, 60, Density(1f)) { LevelBar(onHand, required, preferred, overflow) }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            // Sampled at the top edge of the bar, above the text, at a fraction of the bar's own width.
            return { fraction -> bitmap.getColor((BAR_WIDTH * fraction).toInt(), 3) }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the bar is filled to the ratio of stock to need, in the colour of its level`() {
        val bar = colors(onHand = 500, required = 1_000) // half: low

        assertThat(bar(0.10f)).isEqualTo(LEVEL_LOW.toArgb())
        assertThat(bar(0.90f)).isEqualTo(LEVEL_TRACK.toArgb())
    }

    @Test
    fun `each level has its own colour`() {
        assertThat(colors(1_000, 1_000)(0.5f)).isEqualTo(LEVEL_COVERED.toArgb())
        assertThat(colors(900, 1_000)(0.5f)).isEqualTo(LEVEL_CLOSE.toArgb())
        assertThat(colors(600, 1_000)(0.5f)).isEqualTo(LEVEL_LOW.toArgb())
        assertThat(colors(100, 1_000)(0.05f)).isEqualTo(LEVEL_SHORT.toArgb())
    }

    @Test
    fun `a short item has a faintly red track, so it stands out even when the bar is almost empty`() {
        assertThat(colors(0, 1_000)(0.5f)).isEqualTo(LEVEL_SHORT_TRACK.toArgb())
        assertThat(colors(1, 2_778_000)(0.5f)).isEqualTo(LEVEL_SHORT_TRACK.toArgb())
        assertThat(colors(500, 1_000)(0.9f)).isEqualTo(LEVEL_TRACK.toArgb()) // only the short level is tinted
    }

    @Test
    fun `stock beyond the overflow level is shown in the surplus colour`() {
        assertThat(colors(onHand = 1_500, required = 1_000, overflow = 1_200)(0.5f)).isEqualTo(LEVEL_SURPLUS.toArgb())
    }

    @Test
    fun `covered stock below the preferred level is a paler green, reaching preferred makes it full green`() {
        val belowPreferred = colors(onHand = 1_000, required = 1_000, preferred = 2_000)
        val atPreferred = colors(onHand = 2_000, required = 1_000, preferred = 2_000)

        assertThat(belowPreferred(0.25f)).isEqualTo(LEVEL_COVERED_PALE.toArgb())
        assertThat(belowPreferred(0.90f)).isEqualTo(LEVEL_TRACK.toArgb()) // the bar is scaled to the preferred level
        assertThat(atPreferred(0.90f)).isEqualTo(LEVEL_COVERED.toArgb())
    }

    /** How many pixels tall the bar is: the opaque pixels down its left edge; the page around it is transparent. */
    private fun heightAt(fontScale: Float): Int {
        val scene = ImageComposeScene(WIDTH, 200, Density(1f, fontScale)) { LevelBar(onHand = 1_000, required = 1_000) }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return (0 until bitmap.height).count { (bitmap.getColor(3, it) ushr 24) != 0 }
        } finally {
            scene.close()
        }
    }

    // Regression guard: the bar had a fixed height, so at the app's doubled text size the numbers were cut off.
    @Test
    fun `the bar grows with the text instead of cutting the numbers off`() {
        val normal = heightAt(fontScale = 1f)
        val large = heightAt(fontScale = 2f)

        assertThat(normal).isGreaterThanOrEqualTo(28)
        assertThat(large).isGreaterThan(normal)
        assertThat(large).isGreaterThanOrEqualTo(2 * 14 + 2 * 3) // two line heights of 14sp text plus its padding
    }

    private companion object {
        const val WIDTH = 400
        const val BAR_WIDTH = 400 // the bar fills the width it is given
    }
}
