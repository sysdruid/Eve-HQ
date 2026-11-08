package eve.hq.app

import eve.hq.data.FileDatabase
import eve.hq.data.StaticTypeNameRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticDataImportCommandTest {
    @Test
    fun `import command loads the archive into the application database`(@TempDir dir: Path) {
        val archive = dir.resolve("sde.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.addJsonLines("categories.jsonl", """{"_key":6,"name":{"en":"Manufacture"}}""")
            zip.addJsonLines("groups.jsonl", """{"_key":18,"categoryID":6,"name":{"en":"Mineral"}}""")
            zip.addJsonLines(
                "types.jsonl",
                """{"_key":34,"groupID":18,"name":{"en":"Tritanium"},"published":true}""",
            )
        }

        runCommand(arrayOf("import-static-data", archive.toString()), dir)

        val database = FileDatabase.open(dir, Clock.systemUTC())
        assertEquals(mapOf(34L to "Tritanium"), StaticTypeNameRepository(database.dataSource).findNames(listOf(34)))
    }

    private fun ZipOutputStream.addJsonLines(name: String, record: String) {
        putNextEntry(ZipEntry(name))
        write("$record\n".toByteArray())
        closeEntry()
    }
}