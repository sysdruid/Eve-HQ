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
    fun `imports the max production limit of a blueprint`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                """{"_key":6,"name":{"en":"Manufacture"},"published":true}""",
                """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}""",
                """{"_key":681,"groupID":18,"name":{"en":"Rifter Blueprint"},"published":true}""",
                blueprint = """{"_key":681,"activities":{"copying":{"time":480},"manufacturing":{"materials":[{"quantity":86,"typeID":38}],"products":[{"quantity":1,"typeID":165}],"time":600}},"blueprintTypeID":681,"maxProductionLimit":300}""",
            ),
        )

        assertEquals(300, importedMaxProductionLimit(681))
    }

    @Test
    fun `imports the manufacturing recipe of a blueprint and ignores its other activities`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE,
                blueprint = """{"_key":681,"activities":{"copying":{"time":480},"manufacturing":{"materials":[{"quantity":86,"typeID":38},{"quantity":10,"typeID":39}],"products":[{"quantity":1,"typeID":165}],"skills":[{"level":1,"typeID":3380}],"time":600},"research_material":{"time":210}},"blueprintTypeID":681,"maxProductionLimit":300}""",
            ),
        )

        assertEquals(
            Recipe(time = 600, materials = mapOf(38L to 86, 39L to 10), products = mapOf(165L to 1)),
            importedRecipe(681, "manufacturing"),
        )
        assertNull(importedRecipe(681, "copying"))
        assertNull(importedRecipe(681, "research_material"))
    }

    @Test
    fun `imports a reaction recipe and keeps references to types the archive does not list`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE,
                blueprint = """{"_key":681,"activities":{"reaction":{"materials":[{"quantity":100,"typeID":16657},{"quantity":100,"typeID":999999}],"products":[{"quantity":20,"typeID":16672}],"time":360}},"blueprintTypeID":681,"maxProductionLimit":1000000}""",
            ),
        )

        assertEquals(
            Recipe(time = 360, materials = mapOf(16657L to 100, 999999L to 100), products = mapOf(16672L to 20)),
            importedRecipe(681, "reaction"),
        )
    }

    @Test
    fun `an activity without inputs or outputs is imported with just its time`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE,
                blueprint = """{"_key":681,"activities":{"manufacturing":{"time":600}},"blueprintTypeID":681,"maxProductionLimit":1}""",
            ),
        )

        assertEquals(Recipe(600, emptyMap(), emptyMap()), importedRecipe(681, "manufacturing"))
    }

    @Test
    fun `a malformed material leaves existing static rows and recipes untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE,
                blueprint = """{"blueprintTypeID":681,"maxProductionLimit":300,"activities":{"manufacturing":{"materials":[{"quantity":86,"typeID":38}],"time":600}}}""",
            ),
        )
        val malformed = archiveWithRecords(
            dir,
            CATEGORY,
            GROUP,
            RIFTER_BLUEPRINT_TYPE,
            blueprint = """{"blueprintTypeID":681,"maxProductionLimit":300,"activities":{"manufacturing":{"materials":[{"typeID":38}],"time":600}}}""",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(malformed) }

        assertEquals(Recipe(600, mapOf(38L to 86), emptyMap()), importedRecipe(681, "manufacturing"))
    }

    @Test
    fun `a re-import replaces the previous recipes`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        fun archive(material: Int, time: Int) = archiveWithRecords(
            dir,
            CATEGORY,
            GROUP,
            RIFTER_BLUEPRINT_TYPE,
            blueprint = """{"blueprintTypeID":681,"maxProductionLimit":300,"activities":{"manufacturing":{"materials":[{"quantity":$material,"typeID":38}],"products":[{"quantity":1,"typeID":165}],"time":$time}}}""",
        )
        importer.importArchive(archive(material = 86, time = 600))

        importer.importArchive(archive(material = 90, time = 700))

        assertEquals(Recipe(700, mapOf(38L to 90), mapOf(165L to 1)), importedRecipe(681, "manufacturing"))
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

    @Test
    fun `a blueprint of an unknown type leaves existing static rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val archiveWithOrphanBlueprint = archiveWithRecords(
            dir,
            """{"_key":6,"name":{"en":"Manufacture"},"published":true}""",
            """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}""",
            """{"_key":35,"groupID":18,"name":{"en":"Pyerite"},"published":true}""",
            blueprint = """{"_key":999,"blueprintTypeID":999,"maxProductionLimit":10}""",
        )

        assertFailsWith<SQLException> { importer.importArchive(archiveWithOrphanBlueprint) }

        assertEquals("Tritanium" to true, importedType(34))
        assertNull(importedType(35))
        assertNull(importedMaxProductionLimit(999))
    }

    @Test
    fun `a re-import replaces the previous blueprints`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        val types = """{"_key":681,"groupID":18,"name":{"en":"Rifter Blueprint"},"published":true}""" + "\n" +
            """{"_key":682,"groupID":18,"name":{"en":"Merlin Blueprint"},"published":true}"""
        val category = """{"_key":6,"name":{"en":"Manufacture"},"published":true}"""
        val group = """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}"""
        importer.importArchive(
            archiveWithRecords(dir, category, group, types, """{"blueprintTypeID":681,"maxProductionLimit":300}"""),
        )

        importer.importArchive(
            archiveWithRecords(dir, category, group, types, """{"blueprintTypeID":682,"maxProductionLimit":50}"""),
        )

        assertNull(importedMaxProductionLimit(681))
        assertEquals(50, importedMaxProductionLimit(682))
    }

    private fun archiveWithRecords(
        dir: Path,
        category: String,
        group: String,
        type: String,
        blueprint: String = "",
        typeDogma: String = "",
    ): Path = archiveWithEntries(
        dir,
        "categories.jsonl" to category,
        "groups.jsonl" to group,
        "types.jsonl" to type,
        "blueprints.jsonl" to blueprint,
        "typeDogma.jsonl" to typeDogma,
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

    private data class Recipe(val time: Int, val materials: Map<Long, Int>, val products: Map<Long, Int>)

    private fun importedRecipe(blueprintTypeId: Long, activity: String): Recipe? = dataSource.connection.use { connection ->
        fun quantities(table: String): Map<Long, Int> = connection.prepareStatement(
            "select type_id, quantity from $table where blueprint_type_id = ? and activity = ?",
        ).use { statement ->
            statement.setLong(1, blueprintTypeId)
            statement.setString(2, activity)
            statement.executeQuery().use { result ->
                buildMap { while (result.next()) put(result.getLong(1), result.getInt(2)) }
            }
        }
        val time = connection.prepareStatement(
            "select time_seconds from blueprint_activities where blueprint_type_id = ? and activity = ?",
        ).use { statement ->
            statement.setLong(1, blueprintTypeId)
            statement.setString(2, activity)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }
        time?.let { Recipe(it, quantities("blueprint_materials"), quantities("blueprint_products")) }
    }

    private fun importedSkills(blueprintTypeId: Long, activity: String): Map<Long, Int> = dataSource.connection.use { connection ->
        connection.prepareStatement("select skill_type_id, level from blueprint_skills where blueprint_type_id = ? and activity = ?").use { statement ->
            statement.setLong(1, blueprintTypeId)
            statement.setString(2, activity)
            statement.executeQuery().use { result -> buildMap { while (result.next()) put(result.getLong(1), result.getInt(2)) } }
        }
    }

    private fun importedTimeBonuses(typeId: Long): Map<Int, Double> = dataSource.connection.use { connection ->
        connection.prepareStatement("select attribute_id, amount from type_time_bonuses where type_id = ?").use { statement ->
            statement.setLong(1, typeId)
            statement.executeQuery().use { result -> buildMap { while (result.next()) put(result.getInt(1), result.getDouble(2)) } }
        }
    }

    private fun importedMaxProductionLimit(typeId: Long): Int? = dataSource.connection.use { connection ->
        connection.prepareStatement("select max_production_limit from blueprints where type_id = ?").use { statement ->
            statement.setLong(1, typeId)
            statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else null }
        }
    }

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

    private companion object {
        const val CATEGORY = """{"_key":6,"name":{"en":"Manufacture"},"published":true}"""
        const val GROUP = """{"_key":18,"categoryID":6,"name":{"en":"Mineral"},"published":true}"""
        const val RIFTER_BLUEPRINT_TYPE = """{"_key":681,"groupID":18,"name":{"en":"Rifter Blueprint"},"published":true}"""
    }

    @Test
    fun `imports the skills a blueprint requires to manufacture or react, and ignores the skills of other activities`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE + "\n" + """{"_key":682,"groupID":18,"name":{"en":"Crystalline Carbonide Reaction Formula"},"published":true}""",
                blueprint = """{"_key":681,"activities":{"invention":{"skills":[{"level":5,"typeID":11450}],"time":900},"manufacturing":{"materials":[{"quantity":86,"typeID":38}],"products":[{"quantity":1,"typeID":165}],"skills":[{"level":1,"typeID":3380},{"level":3,"typeID":11453}],"time":600}},"blueprintTypeID":681,"maxProductionLimit":300}""" +
                    "\n" + """{"_key":682,"activities":{"reaction":{"materials":[{"quantity":100,"typeID":16633}],"products":[{"quantity":200,"typeID":16672}],"skills":[{"level":3,"typeID":45746}],"time":10800}},"blueprintTypeID":682,"maxProductionLimit":1}""",
            ),
        )

        assertEquals(mapOf(3380L to 1, 11453L to 3), importedSkills(681, "manufacturing"))
        assertEquals(mapOf(45746L to 3), importedSkills(682, "reaction"))
        assertEquals(emptyMap(), importedSkills(681, "invention"))
    }

    @Test
    fun `imports only the attributes that shorten jobs, from every type that has them`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)

        StaticDataImporter(dataSource).importArchive(
            archiveWithRecords(
                dir,
                CATEGORY,
                GROUP,
                RIFTER_BLUEPRINT_TYPE,
                typeDogma = """{"_key":3380,"dogmaAttributes":[{"attributeID":275,"value":1.0},{"attributeID":440,"value":-4.0}],"dogmaEffects":[{"effectID":1}]}""" +
                    "\n" + """{"_key":11453,"dogmaAttributes":[{"attributeID":1982,"value":-1.0},{"attributeID":182,"value":3402.0}]}""" +
                    "\n" + """{"_key":35825,"dogmaAttributes":[{"attributeID":2339,"value":-25.0},{"attributeID":2602,"value":0.85}]}""" +
                    "\n" + """{"_key":99,"dogmaAttributes":[{"attributeID":275,"value":1.0}]}""",
            ),
        )

        assertEquals(mapOf(440 to -4.0), importedTimeBonuses(3380))
        assertEquals(mapOf(1982 to -1.0), importedTimeBonuses(11453))
        assertEquals(mapOf(2602 to 0.85), importedTimeBonuses(35825))
        assertEquals(emptyMap(), importedTimeBonuses(99))
    }

    @Test
    fun `a re-import replaces the previous skills and time bonuses`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        val blueprint = """{"_key":681,"activities":{"manufacturing":{"products":[{"quantity":1,"typeID":165}],"skills":[{"level":1,"typeID":3380}],"time":600}},"blueprintTypeID":681,"maxProductionLimit":300}"""
        importer.importArchive(
            archiveWithRecords(dir, CATEGORY, GROUP, RIFTER_BLUEPRINT_TYPE, blueprint, """{"_key":3380,"dogmaAttributes":[{"attributeID":440,"value":-4.0}]}"""),
        )

        importer.importArchive(
            archiveWithRecords(
                dir, CATEGORY, GROUP, RIFTER_BLUEPRINT_TYPE,
                blueprint.replace("3380", "11453"),
                """{"_key":11453,"dogmaAttributes":[{"attributeID":1982,"value":-1.0}]}""",
            ),
        )

        assertEquals(mapOf(11453L to 1), importedSkills(681, "manufacturing"))
        assertEquals(emptyMap(), importedTimeBonuses(3380))
        assertEquals(mapOf(1982 to -1.0), importedTimeBonuses(11453))
    }

    @Test
    fun `a malformed skill or attribute fails the import and keeps the old data`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val noLevel = archiveWithRecords(
            dir, CATEGORY, GROUP, RIFTER_BLUEPRINT_TYPE,
            """{"_key":681,"activities":{"manufacturing":{"products":[{"quantity":1,"typeID":165}],"skills":[{"typeID":3380}],"time":600}},"blueprintTypeID":681,"maxProductionLimit":300}""",
        )
        val noValue = archiveWithRecords(
            dir, CATEGORY, GROUP, RIFTER_BLUEPRINT_TYPE,
            typeDogma = """{"_key":3380,"dogmaAttributes":[{"attributeID":440}]}""",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(noLevel) }
        assertFailsWith<IllegalArgumentException> { importer.importArchive(noValue) }

        assertEquals("Tritanium" to true, importedType(34))
    }

    @Test
    fun `an archive without the dogma entry is rejected and leaves existing rows untouched`(@TempDir dir: Path) {
        Migrations.migrate(dataSource)
        val importer = StaticDataImporter(dataSource)
        importer.importArchive(existingTypeArchive(dir))
        val withoutDogma = archiveWithEntries(
            dir,
            "categories.jsonl" to CATEGORY,
            "groups.jsonl" to GROUP,
            "types.jsonl" to RIFTER_BLUEPRINT_TYPE,
            "blueprints.jsonl" to "",
        )

        assertFailsWith<IllegalArgumentException> { importer.importArchive(withoutDogma) }

        assertEquals("Tritanium" to true, importedType(34))
    }
}
