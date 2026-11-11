package eve.hq.data

import eve.hq.core.production.Stockpile
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class JooqStockpileRepositoryTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val repository = JooqStockpileRepository(dataSource)

    @Test
    fun `saved stockpiles come back with their optional levels`() {
        val full = Stockpile(100, "Multifrequency L", required = 100, preferred = 300, overflow = 1_000)
        val minimal = Stockpile(200, "Fuel blocks", required = 5_000_000_000)

        repository.save(full)
        repository.save(minimal)

        assertThat(repository.all()).containsExactlyInAnyOrder(full, minimal)
    }

    @Test
    fun `saving an item again replaces its levels, including clearing optional ones`() {
        repository.save(Stockpile(100, "Multifrequency L", required = 100, preferred = 300, overflow = 1_000))

        repository.save(Stockpile(100, "Multifrequency L", required = 150))

        assertThat(repository.all()).containsExactly(Stockpile(100, "Multifrequency L", required = 150))
    }

    @Test
    fun `a removed stockpile is gone and others stay`() {
        repository.save(Stockpile(100, "Multifrequency L", required = 100))
        repository.save(Stockpile(200, "Fuel blocks", required = 10))

        repository.remove(100)

        assertThat(repository.all().map { it.item }).containsExactly(200L)
    }
}
