package eve.hq.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eve.hq.app.levels.Level
import eve.hq.app.levels.compact
import eve.hq.app.levels.levelOf
import java.util.Locale

// Fixed pastel colours with dark text, like the job lines: readable whatever the theme.
internal val LEVEL_TRACK = Color(0xFFF1F3F4)
internal val LEVEL_COVERED = Color(0xFFA5D6A7)
internal val LEVEL_COVERED_PALE = Color(0xFFD7EDD8)
internal val LEVEL_CLOSE = Color(0xFFFFF59D)
internal val LEVEL_LOW = Color(0xFFFFCC80)
internal val LEVEL_SHORT = Color(0xFFEF9A9A)
internal val LEVEL_SHORT_TRACK = Color(0xFFFCE4E4)
internal val LEVEL_SURPLUS = Color(0xFF90CAF9)
private val TICK = Color(0xFF455A64)
private val TEXT = Color(0xFF1B1B1B)

/**
 * What is on hand against what is needed, as a bar filled to that ratio and coloured by how well it is covered. With a
 * [preferred] level the bar is scaled to it, a tick marks the required level, and covered stock short of preferred is a paler
 * green; above [overflow] the bar turns blue. The exact numbers are in the tooltip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LevelBar(onHand: Long, required: Long, preferred: Long? = null, overflow: Long? = null, modifier: Modifier = Modifier) {
    val scale = maxOf(required, preferred ?: 0L, 1L)
    val fill = (onHand.toDouble() / scale).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        overflow != null && onHand > overflow -> LEVEL_SURPLUS
        else -> when (levelOf(onHand, required)) {
            Level.Covered -> if (preferred != null && onHand < preferred) LEVEL_COVERED_PALE else LEVEL_COVERED
            Level.Close -> LEVEL_CLOSE
            Level.Low -> LEVEL_LOW
            Level.Short -> LEVEL_SHORT
        }
    }
    // A short item is almost all track, so the track itself carries the warning.
    val track = if (levelOf(onHand, required) == Level.Short) LEVEL_SHORT_TRACK else LEVEL_TRACK
    TooltipArea(tooltip = { Tooltip(onHand, required, preferred, overflow) }) {
        Box(modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
            // matchParentSize pins the constraints to the full bar, so the fraction must be applied to a child.
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(color))
            }
            if (preferred != null && preferred > required) {
                val tickAt = (required.toDouble() / scale).toFloat().coerceIn(0.01f, 0.99f)
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(tickAt))
                    Box(Modifier.width(2.dp).fillMaxHeight().background(TICK))
                    Spacer(Modifier.weight(1f - tickAt))
                }
            }
            Text("${compact(onHand)} / ${compact(required)}", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = TEXT)
        }
    }
}

@Composable
private fun Tooltip(onHand: Long, required: Long, preferred: Long?, overflow: Long?) {
    Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.inverseSurface) {
        val lines = buildList {
            add("On hand ${exact(onHand)} of ${exact(required)} needed")
            preferred?.takeIf { it > required }?.let { add("Preferred ${exact(it)}") }
            overflow?.let { add("Surplus above ${exact(it)}") }
        }
        Text(lines.joinToString("\n"), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}

private fun exact(value: Long) = "%,d".format(Locale.ROOT, value)
