package eve.hq.app

import com.sun.net.httpserver.HttpServer
import eve.hq.data.FileDatabase
import eve.hq.data.StaticTypeNameRepository
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StaticDataImportCommandTest {
    @Test
    fun `import command defaults to CCP latest JSONL archive`(@TempDir dir: Path) {
        assertEquals("https://developers.eveonline.com/static-data/eve-online-static-data-latest-jsonl.zip", LATEST_SDE_URL)
        TestSdeServer().use { server ->
            runCommand(arrayOf("import-static-data"), dir, server.url)

            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
        }
    }

    @Test
    fun `import command downloads a JSONL archive from a URL`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.importTo(dir)

            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
        }
    }

    @Test
    fun `unchanged remote archive is not downloaded or imported again`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.lastModified = "Wed, 04 Nov 2026 12:14:06 GMT"
            server.importTo(dir)
            server.importTo(dir)

            assertEquals(listOf(null, "\"version-1\""), server.etags)
            assertEquals(listOf(null, "Wed, 04 Nov 2026 12:14:06 GMT"), server.modifiedSince)
            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
        }
    }

    @Test
    fun `a changed remote archive replaces the dataset and its validator`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.importTo(dir)
            server.etag = "\"version-2\""
            server.bytes = archiveBytes("Pyerite")

            server.importTo(dir)
            server.importTo(dir)

            assertEquals(listOf(null, "\"version-1\"", "\"version-2\""), server.etags)
            assertEquals(mapOf(34L to "Pyerite"), importedTypes(dir))
        }
    }

    @Test
    fun `a malformed downloaded archive leaves the old dataset and validator intact`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.importTo(dir)
            server.etag = "\"version-2\""
            server.bytes = archiveBytes(typeRecord = """{"_key":34""")

            assertFailsWith<IllegalArgumentException> { server.importTo(dir) }
            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))

            server.bytes = archiveBytes("Pyerite")
            server.importTo(dir)
            assertEquals(listOf(null, "\"version-1\"", "\"version-1\""), server.etags)
            assertEquals(mapOf(34L to "Pyerite"), importedTypes(dir))
        }
    }

    @Test
    fun `a local import invalidates the remote validator`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.importTo(dir)
            val localArchive = Files.write(dir.resolve("local.zip"), archiveBytes("Local type"))
            runCommand(arrayOf("import-static-data", localArchive.toString()), dir)

            server.importTo(dir)

            assertEquals(listOf<String?>(null, null), server.etags)
            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
        }
    }

    @Test
    fun `HTTP errors leave the old dataset and validator intact`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.importTo(dir)
            server.status = 503

            val error = assertFailsWith<IllegalStateException> { server.importTo(dir) }
            assertEquals("Static-data download failed: HTTP 503", error.message)
            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))

            server.status = 200
            server.importTo(dir)
            assertEquals(listOf(null, "\"version-1\"", "\"version-1\""), server.etags)
        }
    }

    @Test
    fun `a Last-Modified validator works without an ETag`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.etag = null
            server.lastModified = "Wed, 04 Nov 2026 12:14:06 GMT"

            server.importTo(dir)
            server.importTo(dir)

            assertEquals(listOf(null, "Wed, 04 Nov 2026 12:14:06 GMT"), server.modifiedSince)
            assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
        }
    }

    @Test
    fun `validators from a different URL are not reused`(@TempDir dir: Path) {
        TestSdeServer().use { original ->
            TestSdeServer().use { replacement ->
                original.importTo(dir)
                replacement.bytes = archiveBytes("Pyerite")

                replacement.importTo(dir)

                assertEquals(listOf<String?>(null), replacement.etags)
                assertEquals(mapOf(34L to "Pyerite"), importedTypes(dir))
            }
        }
    }

    @Test
    fun `an unexpected not modified response is rejected`(@TempDir dir: Path) {
        TestSdeServer().use { server ->
            server.status = 304

            val error = assertFailsWith<IllegalStateException> { server.importTo(dir) }

            assertEquals("Static-data download failed: HTTP 304", error.message)
            assertEquals(listOf<String?>(null), server.etags)
        }
    }

    @Test
    fun `import command loads the archive into the application database`(@TempDir dir: Path) {
        val archive = dir.resolve("sde.zip")
        Files.write(archive, archiveBytes())

        runCommand(arrayOf("import-static-data", archive.toString()), dir)

        assertEquals(mapOf(34L to "Tritanium"), importedTypes(dir))
    }

    private fun importedTypes(dir: Path): Map<Long, String> =
        StaticTypeNameRepository(FileDatabase.open(dir, Clock.systemUTC()).dataSource).findNames(listOf(34))

    private fun archiveBytes(
        typeName: String = "Tritanium",
        typeRecord: String = """{"_key":34,"groupID":18,"name":{"en":"$typeName"},"published":true}""",
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.addJsonLines("categories.jsonl", """{"_key":6,"name":{"en":"Manufacture"}}""")
            zip.addJsonLines("groups.jsonl", """{"_key":18,"categoryID":6,"name":{"en":"Mineral"}}""")
            zip.addJsonLines("types.jsonl", typeRecord)
            zip.addJsonLines("blueprints.jsonl", "")
            zip.addJsonLines("typeDogma.jsonl", "")
        }
    }.toByteArray()

    private inner class TestSdeServer : AutoCloseable {
        @Volatile var etag: String? = "\"version-1\""
        @Volatile var lastModified: String? = null
        @Volatile var bytes: ByteArray = archiveBytes()
        @Volatile var status: Int = 200
        val etags = CopyOnWriteArrayList<String?>()
        val modifiedSince = CopyOnWriteArrayList<String?>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/latest") { exchange ->
                exchange.responseHeaders.add("Location", "/sde.zip")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/sde.zip") { exchange ->
                val requestedEtag = exchange.requestHeaders.getFirst("If-None-Match")
                val requestedModified = exchange.requestHeaders.getFirst("If-Modified-Since")
                etags.add(requestedEtag)
                modifiedSince.add(requestedModified)
                if (status != 200) {
                    exchange.sendResponseHeaders(status, -1)
                    exchange.close()
                } else if ((requestedEtag != null && requestedEtag == etag) ||
                    (requestedEtag == null && requestedModified != null && requestedModified == lastModified)
                ) {
                    exchange.sendResponseHeaders(304, -1)
                    exchange.close()
                } else {
                    etag?.let { exchange.responseHeaders.add("ETag", it) }
                    lastModified?.let { exchange.responseHeaders.add("Last-Modified", it) }
                    val content = bytes
                    exchange.sendResponseHeaders(200, content.size.toLong())
                    exchange.responseBody.use { it.write(content) }
                }
            }
            start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/latest"
        fun importTo(dir: Path) = runCommand(arrayOf("import-static-data", url), dir)
        override fun close() = server.stop(0)
    }

    private fun ZipOutputStream.addJsonLines(name: String, record: String) {
        putNextEntry(ZipEntry(name))
        write("$record\n".toByteArray())
        closeEntry()
    }
}