package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
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

    // Regression guard: H2 2.4.240 broke inserts into tables with a CHECK constraint once the connection
    // that created the table (the migration connection) had closed.
    @Test
    fun `after migrating, a new connection can insert a row that satisfies the status check`() {
        Migrations.migrate(dataSource)

        dataSource.connection.use { c ->
            c.createStatement().executeUpdate(
                "insert into characters (id, name, access_token, refresh_token, token_expires_at, status) " +
                    "values (1, 'Alice', 'a', 'r', timestamp with time zone '2026-01-01 00:00:00+00', 'VALID')",
            )
        }

        assertEquals(1, query("select count(*) from characters") { rs -> rs.next(); rs.getInt(1) })
    }
}
