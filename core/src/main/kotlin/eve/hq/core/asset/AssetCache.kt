package eve.hq.core.asset

import java.time.Instant

data class AssetSnapshot(val items: List<AssetItem>, val updatedAt: Instant)

data class CachedAssetPage(
    val page: Int,
    val body: String,
    val etag: String?,
    val expiresAt: Instant?,
    val pageCount: Int,
)

interface AssetCache {
    fun findSnapshot(characterId: Long): AssetSnapshot?

    fun findPage(characterId: Long, page: Int): CachedAssetPage?

    fun replace(characterId: Long, snapshot: AssetSnapshot, pages: List<CachedAssetPage>)
}
