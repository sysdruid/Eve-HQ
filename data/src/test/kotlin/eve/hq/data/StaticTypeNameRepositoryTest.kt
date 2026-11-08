package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticTypeNameRepositoryTest {
    @Test
    fun `shared fixture provides canonical material type names`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        Migrations.migrate(dataSource)
        val engine = JDBEngine.builder(IStaticTypesTestSchema::class.java).dataSource(dataSource).build()
        engine.resetDB { db ->
            db.include(StandardStaticTypes::class.java)
        }

        assertEquals(
            mapOf(34L to "Tritanium", 35L to "Pyerite"),
            StaticTypeNameRepository(dataSource).findNames(listOf(34, 35)),
        )
    }
}