package eve.hq.data

import eve.hq.core.blueprint.CachedBlueprintPage
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class JooqBlueprintPageCacheTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val characters = JooqCharacterRepository(dataSource, TokenCipher(ByteArray(32) { it.toByte() }))
    private val cache = JooqBlueprintPageCache(dataSource)
    private val expires = Instant.parse("2026-11-08T13:00:00Z")

    private fun page(number: Int, body: String = "[]") = CachedBlueprintPage(number, body, "\"p$number\"", expires, 2)

    @Test
    fun `saved pages are found and a later replace drops pages that are no longer there`() {
        characters.save(alice())
        cache.replace(1, listOf(page(1, "[1]"), page(2, "[2]"), page(3, "[3]")))
        assertThat(cache.findPage(1, 2)).isEqualTo(page(2, "[2]"))

        cache.replace(1, listOf(page(1, "[new]"), CachedBlueprintPage(2, "[x]", null, null, 2)))

        assertThat(cache.findPage(1, 1)).isEqualTo(page(1, "[new]"))
        assertThat(cache.findPage(1, 2)).isEqualTo(CachedBlueprintPage(2, "[x]", null, null, 2))
        assertThat(cache.findPage(1, 3)).isNull()
    }

    @Test
    fun `removing a character deletes its cached pages`() {
        characters.save(alice())
        cache.replace(1, listOf(page(1)))

        characters.remove(1)

        assertThat(cache.findPage(1, 1)).isNull()
    }

    private fun alice() = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.parse("2026-01-01T00:00:00Z")))
}
