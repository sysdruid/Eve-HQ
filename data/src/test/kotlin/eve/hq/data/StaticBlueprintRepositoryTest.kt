package eve.hq.data

import org.h2.jdbcx.JdbcDataSource
import org.jdbscript.JDBEngine
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StaticBlueprintRepositoryTest {
    @Test
    fun `max runs are found by blueprint type id and unknown types are left out`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        }
        Migrations.migrate(dataSource)
        JDBEngine.builder(IStaticTypesTestSchema::class.java).dataSource(dataSource).build().resetDB { db ->
            db.categories().id(9).name("Blueprint")
            db.groups().id(105).category_id(9).name("Frigate Blueprint")
            db.types().id(681).group_id(105).name("Rifter Blueprint").published(true)
            db.types().id(682).group_id(105).name("Merlin Blueprint").published(true)
            db.blueprints().type_id(681).max_production_limit(300)
        }

        assertEquals(mapOf(681L to 300), StaticBlueprintRepository(dataSource).findMaxRuns(listOf(681, 682, 999)))
    }
}
