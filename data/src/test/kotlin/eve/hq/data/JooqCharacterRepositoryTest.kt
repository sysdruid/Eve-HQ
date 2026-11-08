package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JooqCharacterRepositoryTest {
    private val dataSource: DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)

    private val engine = JDBEngine.builder(ICharacterTestSchema::class.java).dataSource(dataSource).build()
    private val repository = JooqCharacterRepository(dataSource, TokenCipher(ByteArray(32) { it.toByte() }))

    private val alice = EveCharacter(
        id = 1,
        name = "Alice",
        token = AuthToken("access-secret", "refresh-secret", Instant.parse("2026-01-01T00:00:00Z")),
    )

    private fun rawTokens(id: Long): Pair<String, String> = dataSource.connection.use { c ->
        c.createStatement().executeQuery("select access_token, refresh_token from characters where id = $id").use { rs ->
            rs.next()
            rs.getString(1) to rs.getString(2)
        }
    }

    @Test
    fun `tokens are encrypted in the database and read back as stored`() {
        repository.save(alice)

        val (access, refresh) = rawTokens(1)
        assertTrue(access.startsWith("v1:") && refresh.startsWith("v1:"), "access=$access refresh=$refresh")
        assertFalse("access-secret" in access || "refresh-secret" in refresh)
        assertEquals(alice, repository.find(1))
    }

    @Test
    fun `a token stored without encryption is read as it is`() {
        engine.insertDB { db ->
            db.characters().id(2).name("Bob").access_token("old-access").refresh_token("old-refresh")
                .token_expires_at(Instant.parse("2026-01-01T00:00:00Z")).status("VALID")
        }

        assertEquals(AuthToken("old-access", "old-refresh", Instant.parse("2026-01-01T00:00:00Z")), repository.find(2)?.token)
    }
}
