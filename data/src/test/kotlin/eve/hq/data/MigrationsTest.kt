package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationsTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { c -> c.createStatement().executeQuery(sql).use(read) }

    @Test
    fun `migrations apply to an empty database and create the characters table`() {
        Migrations.migrate(dataSource)

        val tables = query("select table_name from information_schema.tables where table_schema = 'PUBLIC'") { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
        assertTrue("CHARACTERS" in tables, "tables: $tables")
    }

    @Test
    fun `migrations create static type hierarchy tables`() {
        Migrations.migrate(dataSource)

        val tables = query("select table_name from information_schema.tables where table_schema = 'PUBLIC'") { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }

        assertTrue(setOf("CATEGORIES", "GROUPS", "TYPES").all(tables::contains), "tables: $tables")
    }

    // Regression guard: H2 2.4.240 broke inserts into tables with a CHECK constraint once the connection
    // that created the table (the migration connection) had closed.
    @Test
    fun `after migrating, a new connection can insert a row that satisfies the status check`() {
        Migrations.migrate(dataSource)

        JDBEngine.builder(ICharacterTestSchema::class.java).dataSource(dataSource).build().insertDB { db ->
            db.characters().id(1).name("Alice").status("VALID")
        }

        assertEquals(1, query("select count(*) from characters") { rs -> rs.next(); rs.getInt(1) })
    }
}
