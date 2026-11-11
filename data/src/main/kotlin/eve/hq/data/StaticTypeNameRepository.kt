package eve.hq.data

import eve.hq.core.production.ItemType
import eve.hq.core.production.TypeCatalog
import eve.hq.data.jooq.tables.Types.TYPES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class StaticTypeNameRepository(dataSource: DataSource) : TypeCatalog {
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

    override fun search(query: String): List<ItemType> {
        if (query.isBlank()) return emptyList()
        return dsl.select(TYPES.ID, TYPES.NAME)
            .from(TYPES)
            .where(TYPES.PUBLISHED.isTrue)
            .and(TYPES.NAME.containsIgnoreCase(query.trim()))
            .orderBy(DSL.length(TYPES.NAME), TYPES.NAME)
            .limit(SEARCH_LIMIT)
            .fetch { record -> ItemType(record.value1(), record.value2()) }
    }

    companion object {
        private const val QUERY_BATCH_SIZE = 1000
        private const val SEARCH_LIMIT = 25
    }
}