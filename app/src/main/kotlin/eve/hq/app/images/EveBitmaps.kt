package eve.hq.app.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/** Decoded pictures ready to draw, with the most recently used kept in memory. */
class EveBitmaps(private val images: EveImages, private val capacity: Int = 600) {
    // Access-ordered, so the entry dropped when full is the one least recently drawn.
    private val decoded = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > capacity
    }

    suspend fun typeIcon(typeId: Long, size: Int = 64): ImageBitmap? = bitmap("t$typeId-$size") { images.typeIcon(typeId, size) }

    suspend fun portrait(characterId: Long, size: Int = 64): ImageBitmap? = bitmap("c$characterId-$size") { images.portrait(characterId, size) }

    private suspend fun bitmap(key: String, bytes: suspend () -> ByteArray?): ImageBitmap? {
        synchronized(decoded) { decoded[key] }?.let { return it }
        val encoded = bytes() ?: return null
        // A picture the server sent damaged must leave a gap, not break the screen.
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { Image.makeFromEncoded(encoded).toComposeImageBitmap() }.getOrNull()
        } ?: return null
        synchronized(decoded) { decoded[key] = bitmap }
        return bitmap
    }
}
