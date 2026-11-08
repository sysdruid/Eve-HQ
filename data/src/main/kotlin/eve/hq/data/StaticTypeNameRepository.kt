package eve.hq.data

import eve.hq.data.jooq.tables.Types.TYPES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class StaticTypeNameRepository(dataSource: DataSource) {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    fun findNames(typeIds: List<Long>): Map<Long, String> = typeIds.distinct().chunked(QUERY_BATCH_SIZE)
        .flatMap { ids ->
            dsl.select(TYPES.ID, TYPES.NAME)
                .from(TYPES)
                .where(TYPES.ID.`in`(ids))
                .fetch { record -> record.value1() to record.value2() }
        }
        .toMap()

    companion object {
        private const val QUERY_BATCH_SIZE = 1000
    }
}