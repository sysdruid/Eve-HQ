package eve.hq.data.esi

import eve.hq.core.asset.AssetAccessDenied
import eve.hq.core.asset.AssetCache
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSnapshot
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.asset.CachedAssetPage
import eve.hq.core.character.EveCharacter
import eve.hq.data.LocationNameCache
import eve.hq.data.StaticTypeNameRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.Instant

class EveAssetSource(
    private val http: HttpClient,
    baseUrl: String = "https://esi.evetech.net/latest/",
    staticTypeNames: StaticTypeNameRepository? = null,
    private val assetCache: AssetCache? = null,
    private val clock: Clock = Clock.systemUTC(),
    locationNames: LocationNameCache? = null,
) : AssetSource {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }
    private val names = EsiNames(http, baseUrl, staticTypeNames, locationNames, clock)

    override fun cachedSnapshot(characterId: Long) = assetCache?.findSnapshot(characterId)

    override suspend fun load(character: EveCharacter): List<AssetItem> {
        freshSnapshot(character.id)?.let { return it.items }
        val loadedPages = loadPages(character)
        val assets = loadedPages.assets
        val typeIds = assets.map(EsiAsset::typeId).distinct()
        val typeNames = names.typeNames(typeIds)
        val itemNames = loadItemNames(character, assets.map(EsiAsset::itemId))
        val locationNames = loadLocationNames(character, assets)
        val itemIds = assets.mapTo(mutableSetOf(), EsiAsset::itemId)
        val items = assets.map { asset ->
            AssetItem(
                itemId = asset.itemId,
                type = AssetType(asset.typeId, typeNames[asset.typeId]),
                customName = itemNames[asset.itemId],
                quantity = asset.quantity,
                location = AssetLocation(
                    asset.locationId,
                    if (asset.locationId in itemIds) null else locationNames[asset.locationId],
                ),
            )
        }
        assetCache?.replace(character.id, AssetSnapshot(items, clock.instant()), loadedPages.pages)
        return items
    }

    /** ESI cannot have changed while every page is unexpired, so the saved snapshot (names included) is still exact. */
    private fun freshSnapshot(characterId: Long): AssetSnapshot? {
        val cache = assetCache ?: return null
        val pageCount = cache.findPage(characterId, 1)?.pageCount ?: return null
        val allFresh = (1..pageCount).all { cache.findPage(characterId, it)?.expiresAt?.isAfter(clock.instant()) == true }
        return if (allFresh) cache.findSnapshot(characterId) else null
    }

    private suspend fun loadPages(character: EveCharacter): LoadedAssetPages {
        val assets = mutableListOf<EsiAsset>()
        val pages = mutableListOf<CachedAssetPage>()
        var pageCount = 1
        var page = 1
        while (page <= pageCount) {
            val cached = assetCache?.findPage(character.id, page)
            val cachedIsFresh = cached?.expiresAt?.isAfter(clock.instant()) == true
            val body: String
            val etag: String?
            val expiresAt: Instant?
            if (cachedIsFresh) {
                body = cached.body
                etag = cached.etag
                expiresAt = cached.expiresAt
                if (page == 1) pageCount = cached.pageCount
            } else {
                val response = http.get(url("characters/${character.id}/assets/")) {
                    bearerAuth(character.token.accessToken)
                    parameter("page", page)
                    cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                }
                when (response.status) {
                    HttpStatusCode.NotModified -> {
                        if (cached == null) throw EsiRequestFailed("character assets", response.status.value, "")
                        body = cached.body
                        etag = response.headers[HttpHeaders.ETag] ?: cached.etag
                        expiresAt = response.expiresAt()
                        if (page == 1) {
                            pageCount = response.pageCount() ?: cached.pageCount
                        }
                    }

                    else -> {
                        body = response.bodyFor("character assets", requiresAssetScope = true)
                        etag = response.headers[HttpHeaders.ETag]
                        expiresAt = response.expiresAt()
                        if (page == 1) pageCount = response.pageCount() ?: cached?.pageCount ?: 1
                    }
                }
            }
            assets += json.decodeFromString<List<EsiAsset>>(body)
            pages += CachedAssetPage(page, body, etag, expiresAt, pageCount)
            page++
        }
        return LoadedAssetPages(assets, pages)
    }

    private fun HttpResponse.pageCount() = headers["X-Pages"]?.toIntOrNull()?.takeIf { it > 0 }

    private suspend fun loadItemNames(character: EveCharacter, itemIds: List<Long>): Map<Long, String> =
        itemIds.distinct().chunked(LOOKUP_BATCH_SIZE).flatMap { ids ->
            try {
                val response = http.post(url("characters/${character.id}/assets/names/")) {
                    bearerAuth(character.token.accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(ids))
                }
                val body = response.bodyAsText()
                if (response.status.isSuccess()) json.decodeFromString<List<EsiAssetName>>(body) else emptyList()
            } catch (_: IOException) {
                emptyList()
            }
        }.associate { it.itemId to it.name }

    private suspend fun loadLocationNames(character: EveCharacter, assets: List<EsiAsset>): Map<Long, String> {
        val itemIds = assets.mapTo(mutableSetOf(), EsiAsset::itemId)
        val roots = assets.filter { it.locationId !in itemIds }.distinctBy(EsiAsset::locationId)
        return roots.mapNotNull { asset ->
            val name = when {
                asset.locationId >= STRUCTURE_ID_MIN -> names.structureName(asset.locationId, character.token.accessToken)
                asset.locationType == "station" -> names.stationName(asset.locationId)
                asset.locationType == "solar_system" -> names.systemName(asset.locationId)
                else -> null
            }
            name?.let { asset.locationId to it }
        }.toMap()
    }

    private suspend fun HttpResponse.bodyFor(endpoint: String, requiresAssetScope: Boolean = false): String {
        val body = bodyAsText()
        if (!status.isSuccess()) {
            if (requiresAssetScope && (status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized)) {
                throw AssetAccessDenied("EVE denied $endpoint access. Please use Add character to re-authorize asset access.")
            }
            throw EsiRequestFailed(endpoint, status.value, body)
        }
        return body
    }

    private fun url(path: String) = baseUrl + path

    @Serializable
    private data class EsiAsset(
        @SerialName("item_id") val itemId: Long,
        @SerialName("type_id") val typeId: Long,
        val quantity: Int,
        @SerialName("location_id") val locationId: Long,
        @SerialName("location_type") val locationType: String? = null,
    )

    @Serializable
    private data class EsiAssetName(@SerialName("item_id") val itemId: Long, val name: String)

    private data class LoadedAssetPages(val assets: List<EsiAsset>, val pages: List<CachedAssetPage>)

    companion object {
        private const val LOOKUP_BATCH_SIZE = 1000
        private const val STRUCTURE_ID_MIN = 1_000_000_000_000L
    }
}
