package eve.hq.app.images

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test

class EveImagesTest {
    private val picture = byteArrayOf(1, 2, 3, 4)
    private val requested = mutableListOf<String>()
    private var status = HttpStatusCode.OK
    private var failure: Exception? = null

    private fun client() = HttpClient(
        MockEngine { request ->
            requested += request.url.toString()
            delay(20)
            failure?.let { throw it }
            respond(picture, status)
        },
    )

    private fun images(dir: Path) = EveImages(client(), dir, baseUrl = "https://images.test/")

    @Test
    fun `a type icon and a portrait come from the image server at the size asked for`(@TempDir dir: Path) = runBlocking<Unit> {
        val images = images(dir)

        assertThat(images.typeIcon(587, size = 64)).isEqualTo(picture)
        assertThat(images.portrait(96583255, size = 128)).isEqualTo(picture)

        assertThat(requested).containsExactly(
            "https://images.test/types/587/icon?size=64",
            "https://images.test/characters/96583255/portrait?size=128",
        )
    }

    @Test
    fun `a picture is kept on disk, so it is not downloaded again even after a restart`(@TempDir dir: Path) = runBlocking<Unit> {
        images(dir).typeIcon(587)

        val afterRestart = images(dir)
        assertThat(afterRestart.typeIcon(587)).isEqualTo(picture)
        assertThat(afterRestart.typeIcon(587)).isEqualTo(picture)

        assertThat(requested).hasSize(1)
    }

    @Test
    fun `each size is its own picture`(@TempDir dir: Path) = runBlocking<Unit> {
        val images = images(dir)

        images.typeIcon(587, size = 32)
        images.typeIcon(587, size = 64)

        assertThat(requested).hasSize(2)
    }

    @Test
    fun `a type without an icon is asked about once, then known to have none`(@TempDir dir: Path) = runBlocking<Unit> {
        status = HttpStatusCode.NotFound
        val images = images(dir)

        assertThat(images.typeIcon(1)).isNull()
        assertThat(images.typeIcon(1)).isNull()

        assertThat(requested).hasSize(1)
    }

    @Test
    fun `a failing server or network gives no picture now and is tried again next time`(@TempDir dir: Path) = runBlocking<Unit> {
        val images = images(dir)
        failure = IOException("offline")
        assertThat(images.typeIcon(587)).isNull()

        failure = null
        status = HttpStatusCode.InternalServerError
        assertThat(images.typeIcon(587)).isNull()

        status = HttpStatusCode.OK
        assertThat(images.typeIcon(587)).isEqualTo(picture)
        assertThat(requested).hasSize(3)
    }

    @Test
    fun `many rows showing the same icon at once cause one download`(@TempDir dir: Path) = runBlocking<Unit> {
        val images = images(dir)

        val all = List(20) { async { images.typeIcon(587) } }.awaitAll()

        assertThat(all).allMatch { it.contentEquals(picture) }
        assertThat(requested).hasSize(1)
    }

    @Test
    fun `a blueprint has its own original and copy icons, each cached on its own`(@TempDir dir: Path) = runBlocking<Unit> {
        val images = images(dir)

        images.blueprintIcon(681, copy = false)
        images.blueprintIcon(681, copy = true)
        images.blueprintIcon(681, copy = false)

        assertThat(requested).containsExactly(
            "https://images.test/types/681/bp?size=64",
            "https://images.test/types/681/bpc?size=64",
        )
    }
}
