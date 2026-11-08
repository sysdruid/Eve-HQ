package eve.hq.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import eve.hq.app.jobs.JobLine
import eve.hq.app.jobs.JobLineStatus
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.skia.Bitmap
import kotlin.test.Test

@OptIn(ExperimentalComposeUiApi::class)
class JobLineItemTest {
    private val running = 0xFFBBDEFB.toInt()
    private val ready = 0xFFC8E6C9.toInt()
    private val track = 0xFFF1F3F4.toInt()

    @Test
    fun `the bar covers only the completed part of the row`() {
        val row = render(JobLine("Alice-Rifter", JobLineStatus.Running, 0.25f))

        assertThat(row.colorAt(0.10f)).isEqualTo(running)
        assertThat(row.colorAt(0.90f)).isEqualTo(track)
    }

    @Test
    fun `a finished job is green across the whole row and a new job has no bar yet`() {
        assertThat(render(JobLine("done", JobLineStatus.Ready, 1f)).colorAt(0.95f)).isEqualTo(ready)
        assertThat(render(JobLine("new", JobLineStatus.Running, 0f)).colorAt(0.05f)).isEqualTo(track)
    }

    private class Row(private val bitmap: Bitmap) {
        // Sampled near the top edge, inside the padding, so text never covers the pixel.
        fun colorAt(fraction: Float) = bitmap.getColor((bitmap.width * fraction).toInt(), 3)
    }

    private fun render(line: JobLine): Row {
        val scene = ImageComposeScene(WIDTH, 80, Density(1f)) { JobLineItem(line) }
        try {
            return Row(Bitmap.makeFromImage(scene.render()))
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 400
    }
}
