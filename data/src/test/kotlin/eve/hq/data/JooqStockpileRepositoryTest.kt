package eve.hq.data

import eve.hq.core.asset.AssetLocation
import eve.hq.core.production.Stockpile
import eve.hq.core.production.StockpileKey
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

        repository.remove(StockpileKey(100))

        assertThat(repository.all().map { it.item }).containsExactly(200L)
    }

    @Test
    fun `the same item can be kept anywhere and at several stations, each replaced and removed on its own`() {
        val jita = AssetLocation(60000001, "Jita IV")
        val structure = AssetLocation(1_000_000_000_001, null)
        val anywhere = Stockpile(34, "Tritanium", required = 100)
        val inJita = Stockpile(34, "Tritanium", required = 200, station = jita)
        val inStructure = Stockpile(34, "Tritanium", required = 300, station = structure)
        listOf(anywhere, inJita, inStructure).forEach(repository::save)
        assertThat(repository.all()).containsExactlyInAnyOrder(anywhere, inJita, inStructure)

        repository.save(inJita.copy(required = 250))
        repository.remove(StockpileKey(34, structure.id))

        assertThat(repository.all()).containsExactlyInAnyOrder(anywhere, inJita.copy(required = 250))
    }
}
