package eve.hq.data

import eve.hq.data.jooq.tables.Blueprints.BLUEPRINTS
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class StaticBlueprintRepository(dataSource: DataSource) {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    fun findMaxRuns(blueprintTypeIds: List<Long>): Map<Long, Int> = blueprintTypeIds.distinct().chunked(QUERY_BATCH_SIZE)
        .flatMap { ids ->
            dsl.select(BLUEPRINTS.TYPE_ID, BLUEPRINTS.MAX_PRODUCTION_LIMIT)
                .from(BLUEPRINTS)
                .where(BLUEPRINTS.TYPE_ID.`in`(ids))
                .fetch { record -> record.value1() to record.value2() }
        }
        .toMap()

    private companion object {
        const val QUERY_BATCH_SIZE = 1000
    }
}
