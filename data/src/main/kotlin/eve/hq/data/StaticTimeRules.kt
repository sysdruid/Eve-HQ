package eve.hq.data

import eve.hq.core.industry.IndustryActivity
import eve.hq.core.production.TimeRules
import eve.hq.core.production.TypeId
import eve.hq.data.jooq.tables.BlueprintSkills.BLUEPRINT_SKILLS
import eve.hq.data.jooq.tables.TypeTimeBonuses.TYPE_TIME_BONUSES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class StaticTimeRules(dataSource: DataSource) : TimeRules {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun bonus(type: TypeId, attribute: Int): Double? = dsl.select(TYPE_TIME_BONUSES.AMOUNT)
        .from(TYPE_TIME_BONUSES)
        .where(TYPE_TIME_BONUSES.TYPE_ID.eq(type))
        .and(TYPE_TIME_BONUSES.ATTRIBUTE_ID.eq(attribute))
        .fetchOne(TYPE_TIME_BONUSES.AMOUNT)

    override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity): Map<TypeId, Int> {
        val stored = when (activity) {
            IndustryActivity.Manufacturing -> "manufacturing"
            IndustryActivity.Reaction -> "reaction"
            else -> return emptyMap()
        }
        return dsl.select(BLUEPRINT_SKILLS.SKILL_TYPE_ID, BLUEPRINT_SKILLS.LEVEL)
            .from(BLUEPRINT_SKILLS)
            .where(BLUEPRINT_SKILLS.BLUEPRINT_TYPE_ID.eq(blueprint))
            .and(BLUEPRINT_SKILLS.ACTIVITY.eq(stored))
            .fetch { it.value1() to it.value2() }
            .toMap()
    }
}
