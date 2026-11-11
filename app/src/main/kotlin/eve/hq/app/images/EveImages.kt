package eve.hq.app.images

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures from EVE's image server, as the bytes it sends, kept on disk so each is downloaded once. Downloads run in this
 * class's own scope: a screen that is closed while a picture is on its way must not cancel it for the rows still waiting.
 */
class EveImages(
    private val http: HttpClient,
    private val cacheDir: Path,
    baseUrl: String = "https://images.evetech.net/",
) {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(8)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
    private val noPicture = ConcurrentHashMap.newKeySet<String>()

    suspend fun typeIcon(typeId: Long, size: Int = 64): ByteArray? = fetch("types", typeId, size, "icon")

    /** The icon with the blueprint (original) or blueprint copy overlay, as shown in the game. */
    suspend fun blueprintIcon(typeId: Long, copy: Boolean, size: Int = 64): ByteArray? =
        fetch("types", typeId, size, if (copy) "bpc" else "bp")

    suspend fun portrait(characterId: Long, size: Int = 64): ByteArray? = fetch("characters", characterId, size, "portrait")

    private suspend fun fetch(kind: String, id: Long, size: Int, picture: String): ByteArray? {
        val key = "$kind/$picture/$id-$size"
        if (key in noPicture) return null
        val file = cacheDir.resolve("$key.img")
        // Many rows show the same icon at once: only the first asks, the others wait for its answer.
        val mine = CompletableDeferred<ByteArray?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        scope.launch {
            try {
                mine.complete(readFile(file) ?: download("${baseUrl}$kind/$id/$picture?size=$size", key, file))
            } catch (failure: Throwable) {
                mine.complete(null)
            } finally {
                inFlight.remove(key)
            }
        }
        return mine.await()
    }

    private fun readFile(file: Path): ByteArray? = if (Files.exists(file)) Files.readAllBytes(file) else null

    private suspend fun download(url: String, key: String, file: Path): ByteArray? {
        val response = downloads.withPermit { http.get(url) }
        if (response.status == HttpStatusCode.NotFound) {
            noPicture += key
            return null
        }
        if (!response.status.isSuccess()) return null
        val bytes = response.readRawBytes()
        Files.createDirectories(file.parent)
        val partial = file.resolveSibling("${file.fileName}.part")
        Files.write(partial, bytes)
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return bytes
    }
}
