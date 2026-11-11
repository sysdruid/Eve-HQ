package eve.hq.data

import eve.hq.core.production.ItemType
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.util.UUID
import kotlin.test.Test

class StaticTypeSearchTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val catalog = StaticTypeNameRepository(dataSource)

    private fun withTypes(vararg types: Triple<Long, String, Boolean>) {
        JDBEngine.builder(IStaticTypesTestSchema::class.java).dataSource(dataSource).build().resetDB { db ->
            db.categories().id(4).name("Material")
            db.groups().id(18).category_id(4).name("Mineral")
            types.forEach { (id, name, published) -> db.types().id(id).group_id(18).name(name).published(published) }
        }
    }

    @Test
    fun `finds published types by part of the name, ignoring case, shortest names first`() {
        withTypes(
            Triple(1, "Multifrequency L", true),
            Triple(2, "Multifrequency Crystal Blueprint", true),
            Triple(3, "Tritanium", true),
            Triple(4, "Multifrequency Old", false),
        )

        assertThat(catalog.search("multifreq")).containsExactly(
            ItemType(1, "Multifrequency L"),
            ItemType(2, "Multifrequency Crystal Blueprint"),
        )
    }

    @Test
    fun `a blank query finds nothing and the result is capped`() {
        withTypes(*(1L..40L).map { Triple(it, "Crystal $it", true) }.toTypedArray())

        assertThat(catalog.search("  ")).isEmpty()
        assertThat(catalog.search("crystal")).hasSize(25)
    }
}
