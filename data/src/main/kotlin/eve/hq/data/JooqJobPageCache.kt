package eve.hq.data

import eve.hq.core.industry.CachedJobPage
import eve.hq.core.industry.JobPageCache
import eve.hq.data.jooq.tables.CharacterJobPages.CHARACTER_JOB_PAGES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.time.ZoneOffset
import javax.sql.DataSource

class JooqJobPageCache(dataSource: DataSource) : JobPageCache {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun find(characterId: Long): CachedJobPage? = dsl.selectFrom(CHARACTER_JOB_PAGES)
        .where(CHARACTER_JOB_PAGES.CHARACTER_ID.eq(characterId))
        .fetchOne { record ->
            CachedJobPage(
                body = record.get(CHARACTER_JOB_PAGES.BODY),
                etag = record.get(CHARACTER_JOB_PAGES.ETAG),
                expiresAt = record.get(CHARACTER_JOB_PAGES.EXPIRES_AT)?.toInstant(),
            )
        }

    override fun save(characterId: Long, page: CachedJobPage) {
        dsl.mergeInto(CHARACTER_JOB_PAGES)
            .using(DSL.selectOne())
            .on(CHARACTER_JOB_PAGES.CHARACTER_ID.eq(characterId))
            .whenMatchedThenUpdate()
            .set(CHARACTER_JOB_PAGES.BODY, page.body)
            .set(CHARACTER_JOB_PAGES.ETAG, page.etag)
            .set(CHARACTER_JOB_PAGES.EXPIRES_AT, page.expiresAt?.atOffset(ZoneOffset.UTC))
            .whenNotMatchedThenInsert(
                CHARACTER_JOB_PAGES.CHARACTER_ID,
                CHARACTER_JOB_PAGES.BODY,
                CHARACTER_JOB_PAGES.ETAG,
                CHARACTER_JOB_PAGES.EXPIRES_AT,
            )
            .values(characterId, page.body, page.etag, page.expiresAt?.atOffset(ZoneOffset.UTC))
            .execute()
    }
}
