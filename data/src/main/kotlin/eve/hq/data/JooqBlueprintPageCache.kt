package eve.hq.data

import eve.hq.core.blueprint.BlueprintPageCache
import eve.hq.core.blueprint.CachedBlueprintPage
import eve.hq.data.jooq.tables.CharacterBlueprintPages.CHARACTER_BLUEPRINT_PAGES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.time.ZoneOffset
import javax.sql.DataSource

class JooqBlueprintPageCache(dataSource: DataSource) : BlueprintPageCache {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun findPage(characterId: Long, page: Int): CachedBlueprintPage? = dsl.selectFrom(CHARACTER_BLUEPRINT_PAGES)
        .where(CHARACTER_BLUEPRINT_PAGES.CHARACTER_ID.eq(characterId))
        .and(CHARACTER_BLUEPRINT_PAGES.PAGE.eq(page))
        .fetchOne { record ->
            CachedBlueprintPage(
                page = record.get(CHARACTER_BLUEPRINT_PAGES.PAGE),
                body = record.get(CHARACTER_BLUEPRINT_PAGES.BODY),
                etag = record.get(CHARACTER_BLUEPRINT_PAGES.ETAG),
                expiresAt = record.get(CHARACTER_BLUEPRINT_PAGES.EXPIRES_AT)?.toInstant(),
                pageCount = record.get(CHARACTER_BLUEPRINT_PAGES.PAGE_COUNT),
            )
        }

    override fun replace(characterId: Long, pages: List<CachedBlueprintPage>) {
        dsl.transaction { configuration ->
            val transaction = DSL.using(configuration)
            transaction.deleteFrom(CHARACTER_BLUEPRINT_PAGES)
                .where(CHARACTER_BLUEPRINT_PAGES.CHARACTER_ID.eq(characterId))
                .execute()
            val records = pages.map { page ->
                transaction.newRecord(CHARACTER_BLUEPRINT_PAGES).apply {
                    set(CHARACTER_BLUEPRINT_PAGES.CHARACTER_ID, characterId)
                    set(CHARACTER_BLUEPRINT_PAGES.PAGE, page.page)
                    set(CHARACTER_BLUEPRINT_PAGES.BODY, page.body)
                    set(CHARACTER_BLUEPRINT_PAGES.ETAG, page.etag)
                    set(CHARACTER_BLUEPRINT_PAGES.EXPIRES_AT, page.expiresAt?.atOffset(ZoneOffset.UTC))
                    set(CHARACTER_BLUEPRINT_PAGES.PAGE_COUNT, page.pageCount)
                }
            }
            if (records.isNotEmpty()) transaction.batchInsert(records).execute()
        }
    }
}
