package eve.hq.data

import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.TypeId
import eve.hq.data.jooq.tables.Groups.GROUPS
import eve.hq.data.jooq.tables.Types.TYPES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class StaticItemCatalog(dataSource: DataSource) : ItemCatalog {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun itemName(item: TypeId): String? =
        dsl.select(TYPES.NAME).from(TYPES).where(TYPES.ID.eq(item)).fetchOne(TYPES.NAME)

    override fun groupName(item: TypeId): String? = dsl.select(GROUPS.NAME)
        .from(TYPES)
        .join(GROUPS).on(GROUPS.ID.eq(TYPES.GROUP_ID))
        .where(TYPES.ID.eq(item))
        .fetchOne(GROUPS.NAME)

    /** When names collide (the archive keeps retired items), the published one wins, then the lowest id. */
    override fun findItem(name: String): TypeId? = dsl.select(TYPES.ID)
        .from(TYPES)
        .where(TYPES.NAME.equalIgnoreCase(name))
        .orderBy(TYPES.PUBLISHED.desc(), TYPES.ID)
        .limit(1)
        .fetchOne(TYPES.ID)
}
