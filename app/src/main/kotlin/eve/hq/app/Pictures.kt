package eve.hq.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eve.hq.app.images.EveBitmaps

val LocalEveBitmaps = staticCompositionLocalOf<EveBitmaps?> { null }

/** The icon of an item type; a quiet square while it loads, or when the type has none. */
@Composable
fun TypeIcon(typeId: Long?, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val bitmaps = LocalEveBitmaps.current
    val pixels = imagePixels(size)
    val bitmap by produceState<ImageBitmap?>(null, typeId, bitmaps, pixels) {
        value = if (typeId != null) bitmaps?.typeIcon(typeId, pixels) else null
    }
    Picture(bitmap, size, RoundedCornerShape(4.dp), modifier)
}

/** A character's portrait, round. */
@Composable
fun CharacterPortrait(characterId: Long, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val bitmaps = LocalEveBitmaps.current
    val pixels = imagePixels(size)
    val bitmap by produceState<ImageBitmap?>(null, characterId, bitmaps, pixels) { value = bitmaps?.portrait(characterId, pixels) }
    Picture(bitmap, size, CircleShape, modifier)
}

@Composable
private fun Picture(bitmap: ImageBitmap?, size: Dp, shape: Shape, modifier: Modifier) {
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
    }
}

/** The smallest size the image server offers that is not smaller than what is drawn, so pictures stay sharp. */
@Composable
private fun imagePixels(size: Dp): Int {
    val drawn = with(LocalDensity.current) { size.roundToPx() }
    return IMAGE_SIZES.firstOrNull { it >= drawn } ?: IMAGE_SIZES.last()
}

private val IMAGE_SIZES = listOf(32, 64, 128, 256, 512)
