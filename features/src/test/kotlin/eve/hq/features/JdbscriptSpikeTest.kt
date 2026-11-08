package eve.hq.features

import eve.hq.features.support.ICharactersSchema
import eve.hq.features.support.assertHas
import eve.hq.features.support.reset
import eve.hq.features.support.update
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import kotlin.test.Test
import kotlin.test.assertEquals

// Spike: proves jdbscript + Kotlin + H2 2.4 + Flyway V1 work together. Delete once real scenarios exist.
class JdbscriptSpikeTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:spike;DB_CLOSE_DELAY=-1")
    }.also {
        Flyway.configure().dataSource(it).load().migrate()
    }

    private val engine = JDBEngine.builder(ICharactersSchema::class.java).dataSource(dataSource).build()

    private fun names(): List<String> = dataSource.connection.use { c ->
        c.createStatement().executeQuery("select name from characters order by name").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    @Test
    fun `reset seeds rows through the Kotlin receiver DSL`() {
        engine.reset {
            characters().id(1).name("Alice").status("VALID")
            characters().id(2).name("Bob").status("INVALID").invalid_reason("token revoked")
        }

        assertEquals(listOf("Alice", "Bob"), names())
    }

    @Test
    fun `update changes one row and assertDBHas sees it`() {
        engine.reset { characters().id(1).name("Alice").status("VALID") }

        engine.update { characters().id(1).status("INVALID").invalid_reason("revoked") }

        engine.assertHas { characters().id(1).status("INVALID").invalid_reason("revoked") }
    }

    @Test
    fun `plain JDBC insert passes the status check constraint`() {
        dataSource.connection.use { c ->
            c.createStatement().executeUpdate(
                "insert into characters (id, name, access_token, refresh_token, token_expires_at, status) " +
                    "values (1, 'Alice', 'a', 'r', timestamp with time zone '2026-01-01 00:00:00+00', 'VALID')",
            )
        }
        assertEquals(listOf("Alice"), names())
    }
}
