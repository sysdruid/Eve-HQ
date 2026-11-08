package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StaticDataImporterTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `imports the English name of a type from a static data archive`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        StaticDataImporter(dataSource).importArchive(existingTypeArchive(dir))

        assertEquals("Tritanium" to true, importedType(34))
    }

    @Test
    fun `imports unpublished types and ignores additional CCP fields`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                """{"_key":6,"name":{"en":"Manufacture"},"published":true,"extra":123}""",
                """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true,"extra":123}""",
                """{"_key":34,"groupID":18,"name":{"en":"Unpublished Type"},"published":false,"basePrice":100.5,"description":{"en":"unused"}}""",
            ),
        )

        assertEquals("Unpublished Type" to false, importedType(34))
    }

    @Test
    fun `a successful import replaces the previous static rows`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))

        importer.importArchive(
            archiveWithRecords(
                dir,
                """{"_key":6,"name":{"en":"Manufacture"},"published":true}""",
                """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}""",
                """{"_key":35,"groupID":18,"name":{"en":"Pyerite"},"published":true}""",
            ),
        )

        assertNull(importedType(34))
        assertEquals("Pyerite" to true, importedType(35))
    }

    @Test
    fun `a malformed replacement archive leaves existing static rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val malformedArchive = archiveWithRecords(
            dir,
            """{"_key":6,"name":{"en":"Manufacture"},"published":true}""",
            """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}""",
            "{",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(malformedArchive) }

        assertEquals("Tritanium" to true, importedType(34))
        assertNull(importedType(35))
    }

    @Test
    fun `an archive missing a required entry leaves existing static rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val incompleteArchive = archiveWithEntries(
            dir,
            "categories.jsonl" to """{"_key":6,"name":{"en":"Manufacture"}}""",
            "groups.jsonl" to """{"_key":18,"categoryID":6,"name":{"en":"Mineral"}}""",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(incompleteArchive) }

        assertEquals("Tritanium" to true, importedType(34))
        assertNull(importedType(35))
    }

    @Test
    fun `a type without an English name leaves existing static rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val archiveWithoutEnglishName = archiveWithRecords(
            dir,
            """{"_key":7,"name":{"en":"Manufacture"}}""",
            """{"_key":19,"categoryID":7,"name":{"en":"Mineral"}}""",
            """{"_key":35,"groupID":19,"name":{"de":"Neuer Typ"},"published":true}""",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(archiveWithoutEnglishName) }

        assertEquals("Tritanium" to true, importedType(34))
        assertNull(importedType(35))
    }

    @Test
    fun `a type with an unknown group leaves existing static rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val archiveWithUnknownGroup = archiveWithRecords(
            dir,
            """{"_key":7,"name":{"en":"Manufacture"}}""",
            """{"_key":19,"categoryID":7,"name":{"en":"Mineral"}}""",
            """{"_key":35,"groupID":999,"name":{"en":"Unknown Group Type"},"published":true}""",
        )

        assertFailsWith<SQLException> { importer.importArchive(archiveWithUnknownGroup) }

        assertEquals("Tritanium" to true, importedType(34))
        assertEquals(null, importedType(35))
    }

    private fun archiveWithRecords(dir: Path, category: String, group: String, type: String): Path = archiveWithEntries(
        dir,
        "categories.jsonl" to category,
        "groups.jsonl" to group,
        "types.jsonl" to type,
    )

    private fun archiveWithEntries(dir: Path, vararg entries: Pair<String, String>): Path {
        val archive = dir.resolve("sde.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            entries.forEach { (name, record) -> zip.addJsonLines(name, record) }
        }
        return archive
    }

    private fun existingTypeArchive(dir: Path): Path = archiveWithRecords(
        dir,
        """{"_key":6,"name":{"en":"Manufacture"},"published":true}""",
        """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}""",
        """{"_key":34,"groupID":18,"name":{"en":"Tritanium"},"published":true}""",
    )

    private fun importedType(id: Long): Pair<String, Boolean>? = dataSource.connection.use { connection ->
        connection.prepareStatement("select name, published from types where id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("name") to result.getBoolean("published") else null
            }
        }
    }

    private fun ZipOutputStream.addJsonLines(name: String, record: String) {
        putNextEntry(ZipEntry(name))
        write("$record\n".toByteArray())
        closeEntry()
    }
}