package eve.hq.data

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.CachedJobPage
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class JooqJobPageCacheTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val characters = JooqCharacterRepository(dataSource, TokenCipher(ByteArray(32) { it.toByte() }))
    private val cache = JooqJobPageCache(dataSource)

    private val page = CachedJobPage("[{\"job_id\":1}]", "\"v1\"", Instant.parse("2026-11-08T12:05:00Z"))

    @Test
    fun `a saved page is read back and a later save replaces it`() {
        characters.save(alice())

        assertThat(cache.find(1)).isNull()
        cache.save(1, page)
        assertThat(cache.find(1)).isEqualTo(page)
        cache.save(1, CachedJobPage("[]", null, null))
        assertThat(cache.find(1)).isEqualTo(CachedJobPage("[]", null, null))
    }

    @Test
    fun `removing a character deletes its cached page`() {
        characters.save(alice())
        cache.save(1, page)

        characters.remove(1)

        assertThat(cache.find(1)).isNull()
    }

    private fun alice() = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.parse("2026-01-01T00:00:00Z")))
}
