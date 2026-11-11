package eve.hq.data

import eve.hq.core.industry.IndustryActivity
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test

class StaticTimeRulesTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val rules = StaticTimeRules(dataSource)

    private fun sql(statement: String) = dataSource.connection.use { it.createStatement().executeUpdate(statement) }

    init {
        sql("insert into categories (id, name) values (1, 'Category')")
        sql("insert into groups (id, category_id, name) values (1, 1, 'Group')")
        sql("insert into types (id, group_id, name, published) values (100, 1, 'Photon Microprocessor Blueprint', true), (200, 1, 'Carbonide Formula', true)")
        sql("insert into blueprints (type_id, max_production_limit) values (100, 10), (200, 1)")
        sql("insert into blueprint_activities (blueprint_type_id, activity, time_seconds) values (100, 'manufacturing', 75), (200, 'reaction', 10000)")
        sql("insert into blueprint_skills (blueprint_type_id, activity, skill_type_id, level) values (100, 'manufacturing', 3380, 1), (100, 'manufacturing', 11453, 3), (200, 'reaction', 45746, 3)")
        sql("insert into type_time_bonuses (type_id, attribute_id, amount) values (3380, 440, -4.0), (11453, 1982, -1.0), (35825, 2602, 0.85)")
    }

    @Test
    fun `a bonus is the amount the SDE gives for that type and attribute, or none`() {
        assertThat(rules.bonus(3380, 440)).isEqualTo(-4.0)
        assertThat(rules.bonus(35825, 2602)).isEqualTo(0.85)
        assertThat(rules.bonus(3380, 1982)).isNull()
        assertThat(rules.bonus(999, 440)).isNull()
    }

    @Test
    fun `the required skills are those of the blueprint's own activity`() {
        assertThat(rules.requiredSkills(100, IndustryActivity.Manufacturing)).containsExactlyInAnyOrderEntriesOf(mapOf(3380L to 1, 11453L to 3))
        assertThat(rules.requiredSkills(200, IndustryActivity.Reaction)).containsExactlyEntriesOf(mapOf(45746L to 3))
        assertThat(rules.requiredSkills(100, IndustryActivity.Reaction)).isEmpty()
        assertThat(rules.requiredSkills(999, IndustryActivity.Manufacturing)).isEmpty()
    }
}
