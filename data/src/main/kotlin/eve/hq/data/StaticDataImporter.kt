package eve.hq.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
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

data class StaticDataValidators(val etag: String?, val lastModified: String?)

class StaticDataImporter(private val dataSource: DataSource) {
    fun importArchive(archive: Path) = importArchive(archive, null, null)

    fun importDownloadedArchive(archive: Path, source: String, validators: StaticDataValidators) =
        importArchive(archive, source, validators)

    fun validatorsFor(source: String): StaticDataValidators? = dataSource.connection.use { connection ->
        connection.prepareStatement("select etag, last_modified from static_data_import where source = ?").use { statement ->
            statement.setString(1, source)
            statement.executeQuery().use { result ->
                if (result.next()) StaticDataValidators(result.getString(1), result.getString(2)) else null
            }
        }
    }

    private fun importArchive(archive: Path, source: String?, validators: StaticDataValidators?) {
        ZipFile(archive.toFile(), StandardCharsets.UTF_8).use { zip ->
            val entries = REQUIRED_ENTRIES.associateWith { name ->
                zip.getEntry(name)?.takeUnless(ZipEntry::isDirectory)
                    ?: throw IllegalArgumentException("Static-data archive is missing $name")
            }
            dataSource.connection.use { connection ->
                replaceData(connection) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate("delete from blueprint_materials")
                        statement.executeUpdate("delete from blueprint_products")
                        statement.executeUpdate("delete from blueprint_activities")
                        statement.executeUpdate("delete from blueprints")
                        statement.executeUpdate("delete from types")
                        statement.executeUpdate("delete from groups")
                        statement.executeUpdate("delete from categories")
                    }
                    importCategories(zip, entries.getValue(CATEGORIES), connection)
                    importGroups(zip, entries.getValue(GROUPS), connection)
                    importTypes(zip, entries.getValue(TYPES), connection)
                    importBlueprints(zip, entries.getValue(BLUEPRINTS), connection)
                    replaceValidators(connection, source, validators)
                }
            }
        }
    }

    private fun replaceValidators(connection: Connection, source: String?, validators: StaticDataValidators?) {
        connection.createStatement().use { it.executeUpdate("delete from static_data_import") }
        if (source == null || validators == null) return
        connection.prepareStatement(
            "insert into static_data_import (source, etag, last_modified) values (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, source)
            statement.setString(2, validators.etag)
            statement.setString(3, validators.lastModified)
            statement.executeUpdate()
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

    private fun importBlueprints(zip: ZipFile, entry: ZipEntry, connection: Connection) {
        connection.prepareStatement("insert into blueprints (type_id, max_production_limit) values (?, ?)").use { blueprints ->
            connection.prepareStatement(
                "insert into blueprint_activities (blueprint_type_id, activity, time_seconds) values (?, ?, ?)",
            ).use { activities ->
                connection.prepareStatement(
                    "insert into blueprint_materials (blueprint_type_id, activity, type_id, quantity) values (?, ?, ?, ?)",
                ).use { materials ->
                    connection.prepareStatement(
                        "insert into blueprint_products (blueprint_type_id, activity, type_id, quantity) values (?, ?, ?, ?)",
                    ).use { products ->
                        // Parents are flushed before children so the foreign keys hold at every batch.
                        val inserts = listOf(blueprints, activities, materials, products).map(::BatchedInsert)
                        val (blueprintInsert, activityInsert, materialInsert, productInsert) = inserts
                        forEachRecord(zip, entry) { record, line ->
                            val blueprintId = record.requiredLong("blueprintTypeID", BLUEPRINTS, line)
                            blueprintInsert.add {
                                it.setLong(1, blueprintId)
                                it.setInt(2, record.requiredLong("maxProductionLimit", BLUEPRINTS, line).toInt())
                            }
                            RECIPE_ACTIVITIES.forEach { activity ->
                                val recipe = record["activities"]?.jsonObject?.get(activity)?.jsonObject ?: return@forEach
                                activityInsert.add {
                                    it.setLong(1, blueprintId)
                                    it.setString(2, activity)
                                    it.setInt(3, recipe.requiredLong("time", BLUEPRINTS, line).toInt())
                                }
                                recipe.quantities("materials", BLUEPRINTS, line).forEach { (typeId, quantity) ->
                                    materialInsert.add { it.bindRecipeLine(blueprintId, activity, typeId, quantity) }
                                }
                                recipe.quantities("products", BLUEPRINTS, line).forEach { (typeId, quantity) ->
                                    productInsert.add { it.bindRecipeLine(blueprintId, activity, typeId, quantity) }
                                }
                            }
                            if (inserts.any(BatchedInsert::isFull)) inserts.forEach(BatchedInsert::flush)
                        }
                        inserts.forEach(BatchedInsert::flush)
                    }
                }
            }
        }
    }

    private fun PreparedStatement.bindRecipeLine(blueprintId: Long, activity: String, typeId: Long, quantity: Int) {
        setLong(1, blueprintId)
        setString(2, activity)
        setLong(3, typeId)
        setInt(4, quantity)
    }

    private fun JsonObject.quantities(field: String, entry: String, line: Int): List<Pair<Long, Int>> =
        get(field)?.jsonArray.orEmpty().map { item ->
            val material = item.jsonObject
            material.requiredLong("typeID", entry, line) to material.requiredLong("quantity", entry, line).toInt()
        }

    private fun importRecords(
        zip: ZipFile,
        entry: ZipEntry,
        connection: Connection,
        sql: String,
        bind: (PreparedStatement, JsonObject, Int) -> Unit,
    ) {
        connection.prepareStatement(sql).use { statement ->
            val insert = BatchedInsert(statement)
            forEachRecord(zip, entry) { record, line ->
                insert.add { bind(it, record, line) }
                if (insert.isFull) insert.flush()
            }
            insert.flush()
        }
    }

    private fun forEachRecord(zip: ZipFile, entry: ZipEntry, action: (JsonObject, Int) -> Unit) {
        zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEachIndexed { index, text ->
                if (text.isNotBlank()) {
                    val line = index + 1
                    val record = try {
                        Json.parseToJsonElement(text).jsonObject
                    } catch (failure: Exception) {
                        throw IllegalArgumentException("Invalid JSON in ${entry.name} at line $line", failure)
                    }
                    action(record, line)
                }
            }
        }
    }

    private class BatchedInsert(private val statement: PreparedStatement) {
        private var pending = 0

        val isFull get() = pending >= BATCH_SIZE

        fun add(bind: (PreparedStatement) -> Unit) {
            bind(statement)
            statement.addBatch()
            pending++
        }

        fun flush() {
            if (pending > 0) statement.executeBatch()
            pending = 0
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
        private const val BLUEPRINTS = "blueprints.jsonl"
        private const val BATCH_SIZE = 1000
        private val RECIPE_ACTIVITIES = listOf("manufacturing", "reaction")
        private val REQUIRED_ENTRIES = listOf(CATEGORIES, GROUPS, TYPES, BLUEPRINTS)
    }
}