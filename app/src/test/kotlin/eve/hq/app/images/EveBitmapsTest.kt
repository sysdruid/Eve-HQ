package eve.hq.app.images

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class EveBitmapsTest {
    private var body: ByteArray = png(width = 8, height = 6)
    private var requests = 0

    private fun png(width: Int, height: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun bitmaps(dir: Path) = EveBitmaps(
        EveImages(HttpClient(MockEngine { requests++; respond(body, HttpStatusCode.OK) }), dir, "https://images.test/"),
    )

    @Test
    fun `a downloaded picture is decoded into a bitmap of its size`(@TempDir dir: Path) = runBlocking<Unit> {
        val bitmap = bitmaps(dir).typeIcon(587)

        assertThat(bitmap?.width).isEqualTo(8)
        assertThat(bitmap?.height).isEqualTo(6)
    }

    @Test
    fun `a decoded picture is kept, so scrolling back does not decode it again`(@TempDir dir: Path) = runBlocking<Unit> {
        val bitmaps = bitmaps(dir)

        val first = bitmaps.portrait(96583255)
        val second = bitmaps.portrait(96583255)

        assertThat(second).isSameAs(first)
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun `bytes that are not a picture give no bitmap instead of failing the screen`(@TempDir dir: Path) = runBlocking<Unit> {
        body = byteArrayOf(0, 1, 2, 3)

        assertThat(bitmaps(dir).typeIcon(587)).isNull()
    }
}
