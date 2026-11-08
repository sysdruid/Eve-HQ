package eve.hq.data

import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationsTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { c -> c.createStatement().executeQuery(sql).use(read) }

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

    // Regression guard: the blueprint table arrived after static data had already been imported from a download. The
    // saved ETag would make the next import report "already up to date" and never fill it.
    @Test
    fun `migrating past the blueprint table forgets saved download validators so the next import refetches`() {
        Flyway.configure().dataSource(dataSource).locations(Migrations.DEFAULT_LOCATION).target("6").load().migrate()
        dataSource.connection.use { c ->
            c.createStatement().executeUpdate(
                "insert into static_data_import (source, etag, last_modified) values ('https://sde.test/latest.zip', '\"v1\"', 'Sun, 08 Nov 2026 10:00:00 GMT')",
            )
        }

        Migrations.migrate(dataSource)

        assertEquals(0, query("select count(*) from static_data_import") { rs -> rs.next(); rs.getInt(1) })
    }
}
