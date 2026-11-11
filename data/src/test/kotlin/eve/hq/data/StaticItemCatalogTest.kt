package eve.hq.data

import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class StaticItemCatalogTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val catalog = StaticItemCatalog(dataSource)

    private fun sql(statement: String) = dataSource.connection.use { it.createStatement().executeUpdate(statement) }

    init {
        sql("insert into categories (id, name) values (1, 'Material')")
        sql("insert into groups (id, category_id, name) values (18, 1, 'Mineral'), (19, 1, 'Composite')")
        sql("insert into types (id, group_id, name, published) values (34, 18, 'Tritanium', true), (35, 19, 'Old Tritanium', false)")
    }

    @Test
    fun `an item has a name and a group name, and an unknown item has neither`() {
        assertThat(catalog.itemName(34)).isEqualTo("Tritanium")
        assertThat(catalog.groupName(34)).isEqualTo("Mineral")
        assertThat(catalog.itemName(999)).isNull()
        assertThat(catalog.groupName(999)).isNull()
    }

    @Test
    fun `an item is found by its exact name ignoring case`() {
        assertThat(catalog.findItem("tritanium")).isEqualTo(34)
        assertThat(catalog.findItem("Tritan")).isNull()
    }

    @Test
    fun `when names collide the published item is found`() {
        sql("insert into types (id, group_id, name, published) values (36, 18, 'Tritanium', false)")

        assertThat(catalog.findItem("Tritanium")).isEqualTo(34)
    }
}
