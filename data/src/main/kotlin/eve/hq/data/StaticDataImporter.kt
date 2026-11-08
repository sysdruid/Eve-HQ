package eve.hq.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.sql.DataSource

class StaticDataImporter(private val dataSource: DataSource) {
    fun importArchive(archive: Path) {
        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip ->
            val entries = REQUIRED_ENTRIES.associateWith { name ->
                zip.getEntry(name)?.takeUnless(ZipEntry::isDirectory)
                    ?: throw IllegalArgumentException("Static-data archive is missing $name")
            }
            dataSource.connection.use { connection ->
                replaceData(connection) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("delete from types")
                        statement.executeUpdate("delete from groups")
                        statement.executeUpdate("delete from categories")
                    }
                    importCategories(zip, entries.getValue(CATEGORIES), connection)
                    importGroups(zip, entries.getValue(GROUPS), connection)
                    importTypes(zip, entries.getValue(TYPES), connection)
                }
            }
        }
    }

    private fun replaceData(connection: Connection, import: () -> Unit) {
        val originalAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            import()
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = originalAutoCommit
        }
    }

    private fun importCategories(zip: ZipFile, entry: ZipEntry, connection: Connection) {
        importRecords(
            zip,
            entry,
            connection,
            "insert into categories (id, name) values (?, ?)",
        ) { statement, record, line ->
            statement.setLong(1, record.requiredLong("_key", CATEGORIES, line))
            statement.setString(2, record.englishName(CATEGORIES, line))
        }
    }

    private fun importGroups(zip: ZipFile, entry: ZipEntry, connection: Connection) {
        importRecords(
            zip,
            entry,
            connection,
            "insert into groups (id, category_id, name) values (?, ?, ?)",
        ) { statement, record, line ->
            statement.setLong(1, record.requiredLong("_key", GROUPS, line))
            statement.setLong(2, record.requiredLong("categoryID", GROUPS, line))
            statement.setString(3, record.englishName(GROUPS, line))
        }
    }

    private fun importTypes(zip: ZipFile, entry: ZipEntry, connection: Connection) {
        importRecords(
            zip,
            entry,
            connection,
            "insert into types (id, group_id, name, published) values (?, ?, ?, ?)",
        ) { statement, record, line ->
            statement.setLong(1, record.requiredLong("_key", TYPES, line))
            statement.setLong(2, record.requiredLong("groupID", TYPES, line))
            statement.setString(3, record.englishName(TYPES, line))
            statement.setBoolean(4, record.requiredBoolean("published", TYPES, line))
        }
    }

    private fun importRecords(
        zip: ZipFile,
        entry: ZipEntry,
        connection: Connection,
        sql: String,
        bind: (PreparedStatement, JsonObject, Int) -> Unit,
    ) {
        connection.prepareStatement(sql).use { statement ->
            zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                var batched = 0
                lines.forEachIndexed { index, text ->
                    if (text.isNotBlank()) {
                        val line = index + 1
                        val record = try {
                            Json.parseToJsonElement(text).jsonObject
                        } catch (failure: Exception) {
                            throw IllegalArgumentException("Invalid JSON in ${entry.name} at line $line", failure)
                        }
                        bind(statement, record, line)
                        statement.addBatch()
                        batched++
                        if (batched == BATCH_SIZE) {
                            statement.executeBatch()
                            batched = 0
                        }
                    }
                }
                if (batched > 0) statement.executeBatch()
            }
        }
    }

    private fun JsonObject.requiredLong(field: String, entry: String, line: Int): Long =
        get(field)?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Missing or invalid $field in $entry at line $line")

    private fun JsonObject.requiredBoolean(field: String, entry: String, line: Int): Boolean =
        get(field)?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("Missing or invalid $field in $entry at line $line")

    private fun JsonObject.englishName(entry: String, line: Int): String =
        get("name")?.jsonObject?.get("en")?.jsonPrimitive?.takeIf { it.isString }?.content
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing English name in $entry at line $line")

    companion object {
        private const val CATEGORIES = "categories.jsonl"
        private const val GROUPS = "groups.jsonl"
        private const val TYPES = "types.jsonl"
        private const val BATCH_SIZE = 1000
        private val REQUIRED_ENTRIES = listOf(CATEGORIES, GROUPS, TYPES)
    }
}