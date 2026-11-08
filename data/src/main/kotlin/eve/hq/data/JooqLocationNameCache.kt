package eve.hq.data

import eve.hq.data.jooq.tables.LocationNames.LOCATION_NAMES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/** A remembered station, structure or system name; [name] is null when the lookup was refused. */
data class CachedLocationName(val name: String?, val checkedAt: Instant)

interface LocationNameCache {
    fun find(id: Long): CachedLocationName?

    fun save(id: Long, name: String?, checkedAt: Instant)
}

class JooqLocationNameCache(dataSource: DataSource) : LocationNameCache {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun find(id: Long): CachedLocationName? = dsl.selectFrom(LOCATION_NAMES)
        .where(LOCATION_NAMES.ID.eq(id))
        .fetchOne { record ->
            CachedLocationName(record.get(LOCATION_NAMES.NAME), record.get(LOCATION_NAMES.CHECKED_AT).toInstant())
        }

    override fun save(id: Long, name: String?, checkedAt: Instant) {
        val checked = checkedAt.atOffset(ZoneOffset.UTC)
        dsl.mergeInto(LOCATION_NAMES)
            .using(DSL.selectOne())
            .on(LOCATION_NAMES.ID.eq(id))
            .whenMatchedThenUpdate()
            .set(LOCATION_NAMES.NAME, name)
            .set(LOCATION_NAMES.CHECKED_AT, checked)
            .whenNotMatchedThenInsert(LOCATION_NAMES.ID, LOCATION_NAMES.NAME, LOCATION_NAMES.CHECKED_AT)
            .values(id, name, checked)
            .execute()
    }
}
