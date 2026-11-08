package eve.hq.data

import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.Test

class JooqLocationNameCacheTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val cache = JooqLocationNameCache(dataSource)
    private val checked = Instant.parse("2026-11-08T12:00:00Z")

    @Test
    fun `a saved name is found with its check time and a later save replaces it`() {
        assertThat(cache.find(60003760)).isNull()

        cache.save(60003760, "Jita IV - Moon 4", checked)
        assertThat(cache.find(60003760)).isEqualTo(CachedLocationName("Jita IV - Moon 4", checked))

        cache.save(60003760, "Renamed", checked.plusSeconds(60))
        assertThat(cache.find(60003760)).isEqualTo(CachedLocationName("Renamed", checked.plusSeconds(60)))
    }

    @Test
    fun `a location that could not be resolved is remembered without a name`() {
        cache.save(1035466617946, null, checked)

        assertThat(cache.find(1035466617946)).isEqualTo(CachedLocationName(null, checked))
    }
}
