package eve.hq.data

import eve.hq.core.asset.AssetCache
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSnapshot
import eve.hq.core.asset.AssetType
import eve.hq.core.asset.CachedAssetPage
import eve.hq.data.jooq.tables.CharacterAssetPages.CHARACTER_ASSET_PAGES
import eve.hq.data.jooq.tables.CharacterAssetSnapshotItems.CHARACTER_ASSET_SNAPSHOT_ITEMS
import eve.hq.data.jooq.tables.CharacterAssetSnapshots.CHARACTER_ASSET_SNAPSHOTS
import eve.hq.data.jooq.tables.records.CharacterAssetSnapshotItemsRecord
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.time.ZoneOffset
import javax.sql.DataSource

class JooqAssetCacheRepository(dataSource: DataSource) : AssetCache {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun findSnapshot(characterId: Long): AssetSnapshot? {
        val snapshot = dsl.selectFrom(CHARACTER_ASSET_SNAPSHOTS)
            .where(CHARACTER_ASSET_SNAPSHOTS.CHARACTER_ID.eq(characterId))
            .fetchOne() ?: return null
        val items = dsl.selectFrom(CHARACTER_ASSET_SNAPSHOT_ITEMS)
            .where(CHARACTER_ASSET_SNAPSHOT_ITEMS.CHARACTER_ID.eq(characterId))
            .orderBy(CHARACTER_ASSET_SNAPSHOT_ITEMS.ITEM_ID)
            .fetch(::toAssetItem)
        return AssetSnapshot(items, snapshot.get(CHARACTER_ASSET_SNAPSHOTS.UPDATED_AT).toInstant())
    }

    override fun findPage(characterId: Long, page: Int): CachedAssetPage? {
        val record = dsl.selectFrom(CHARACTER_ASSET_PAGES)
            .where(CHARACTER_ASSET_PAGES.CHARACTER_ID.eq(characterId))
            .and(CHARACTER_ASSET_PAGES.PAGE.eq(page))
            .fetchOne() ?: return null
        return CachedAssetPage(
            page = record.get(CHARACTER_ASSET_PAGES.PAGE),
            body = record.get(CHARACTER_ASSET_PAGES.BODY),
            etag = record.get(CHARACTER_ASSET_PAGES.ETAG),
            expiresAt = record.get(CHARACTER_ASSET_PAGES.EXPIRES_AT)?.toInstant(),
            pageCount = record.get(CHARACTER_ASSET_PAGES.PAGE_COUNT),
        )
    }

    override fun replace(characterId: Long, snapshot: AssetSnapshot, pages: List<CachedAssetPage>) {
        dsl.transaction { configuration ->
            val transaction = DSL.using(configuration)
            transaction.deleteFrom(CHARACTER_ASSET_PAGES)
                .where(CHARACTER_ASSET_PAGES.CHARACTER_ID.eq(characterId))
                .execute()
            transaction.deleteFrom(CHARACTER_ASSET_SNAPSHOTS)
                .where(CHARACTER_ASSET_SNAPSHOTS.CHARACTER_ID.eq(characterId))
                .execute()
            transaction.insertInto(CHARACTER_ASSET_SNAPSHOTS)
                .set(CHARACTER_ASSET_SNAPSHOTS.CHARACTER_ID, characterId)
                .set(CHARACTER_ASSET_SNAPSHOTS.UPDATED_AT, snapshot.updatedAt.atOffset(ZoneOffset.UTC))
                .execute()
            insertItems(transaction, characterId, snapshot.items)
            insertPages(transaction, characterId, pages)
        }
    }

    private fun insertItems(transaction: DSLContext, characterId: Long, items: List<AssetItem>) {
        val records = items.map { item ->
            transaction.newRecord(CHARACTER_ASSET_SNAPSHOT_ITEMS).apply {
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.CHARACTER_ID, characterId)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.ITEM_ID, item.itemId)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.TYPE_ID, item.type.id)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.TYPE_NAME, item.type.name)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.CUSTOM_NAME, item.customName)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.QUANTITY, item.quantity)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.LOCATION_ID, item.location.id)
                set(CHARACTER_ASSET_SNAPSHOT_ITEMS.LOCATION_NAME, item.location.name)
            }
        }
        if (records.isNotEmpty()) transaction.batchInsert(records).execute()
    }

    private fun insertPages(transaction: DSLContext, characterId: Long, pages: List<CachedAssetPage>) {
        val records = pages.map { page ->
            transaction.newRecord(CHARACTER_ASSET_PAGES).apply {
                set(CHARACTER_ASSET_PAGES.CHARACTER_ID, characterId)
                set(CHARACTER_ASSET_PAGES.PAGE, page.page)
                set(CHARACTER_ASSET_PAGES.BODY, page.body)
                set(CHARACTER_ASSET_PAGES.ETAG, page.etag)
                set(CHARACTER_ASSET_PAGES.EXPIRES_AT, page.expiresAt?.atOffset(ZoneOffset.UTC))
                set(CHARACTER_ASSET_PAGES.PAGE_COUNT, page.pageCount)
            }
        }
        if (records.isNotEmpty()) transaction.batchInsert(records).execute()
    }

    private fun toAssetItem(record: CharacterAssetSnapshotItemsRecord) = AssetItem(
        itemId = record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.ITEM_ID),
        type = AssetType(
            record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.TYPE_ID),
            record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.TYPE_NAME),
        ),
        customName = record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.CUSTOM_NAME),
        quantity = record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.QUANTITY),
        location = AssetLocation(
            record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.LOCATION_ID),
            record.get(CHARACTER_ASSET_SNAPSHOT_ITEMS.LOCATION_NAME),
        ),
    )
}
