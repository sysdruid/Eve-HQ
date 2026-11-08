package eve.hq.data.esi

import eve.hq.core.asset.AssetAccessDenied
import eve.hq.core.asset.AssetInventory
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetSource
import eve.hq.core.character.EveCharacter
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

class EveAssetSource(
    private val http: HttpClient,
    baseUrl: String = "https://esi.evetech.net/latest/",
) : AssetSource {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(character: EveCharacter): AssetInventory {
        val assets = loadPages(character)
        val typeNames = loadTypeNames(assets.map(EsiAsset::typeId).distinct())
        val itemNames = loadItemNames(character, assets.map(EsiAsset::itemId))
        val locationNames = loadLocationNames(character, assets)
        return AssetInventory(
            items = assets.map { asset ->
                AssetItem(asset.itemId, asset.typeId, asset.quantity, asset.locationId)
            },
            typeNames = typeNames,
            locationNames = locationNames,
            itemNames = itemNames,
        )
    }

    private suspend fun loadPages(character: EveCharacter): List<EsiAsset> {
        val assets = mutableListOf<EsiAsset>()
        var pageCount = 1
        var page = 1
        while (page <= pageCount) {
            val response = http.get(url("characters/${character.id}/assets/")) {
                bearerAuth(character.token.accessToken)
                parameter("page", page)
            }
            val body = response.bodyFor("character assets", requiresAssetScope = true)
            if (page == 1) pageCount = response.headers["X-Pages"]?.toIntOrNull() ?: 1
            assets += json.decodeFromString<List<EsiAsset>>(body)
            page++
        }
        return assets
    }

    private suspend fun loadTypeNames(typeIds: List<Long>): Map<Long, String> = typeIds.chunked(LOOKUP_BATCH_SIZE)
        .flatMap { ids -> loadTypeNamesBatch(ids) }
        .associate { it.id to it.name }

    private suspend fun loadTypeNamesBatch(ids: List<Long>): List<EsiName> {
        val pending = ArrayDeque<List<Long>>()
        val names = mutableListOf<EsiName>()
        pending.addLast(ids)
        var requests = 0
        while (pending.isNotEmpty() && requests < MAX_TYPE_LOOKUPS_PER_BATCH) {
            val batch = pending.removeFirst()
            requests++
            try {
                val response = http.post(url("universe/names/")) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(batch))
                }
                val body = response.bodyAsText()
                when {
                    response.status == HttpStatusCode.NotFound && batch.size > 1 -> {
                        val middle = batch.size / 2
                        // ESI rejects a whole batch for one unknown ID; bound retries if the endpoint itself is unavailable.
                        pending.addLast(batch.take(middle))
                        pending.addLast(batch.drop(middle))
                    }
                    response.status.isSuccess() -> names += json.decodeFromString<List<EsiName>>(body)
                }
            } catch (_: IOException) {
                break
            }
        }
        return names
    }

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
                asset.locationId >= STRUCTURE_ID_MIN -> structureName(asset.locationId, character)
                asset.locationType == "station" -> stationName(asset.locationId)
                asset.locationType == "solar_system" -> systemName(asset.locationId)
                else -> null
            }
            name?.let { asset.locationId to it }
        }.toMap()
    }

    private suspend fun stationName(id: Long): String? = getLocation("universe/stations/$id/")

    private suspend fun systemName(id: Long): String? = getLocation("universe/systems/$id/")

    private suspend fun structureName(id: Long, character: EveCharacter): String? =
        getLocation("universe/structures/$id/", character.token.accessToken)

    private suspend fun getLocation(path: String, accessToken: String? = null): String? = try {
        http.get(url(path)) {
            if (accessToken != null) bearerAuth(accessToken)
        }.locationName()
    } catch (_: IOException) {
        null
    }

    private suspend fun HttpResponse.locationName(): String? {
        val body = bodyAsText()
        return if (status.isSuccess()) json.decodeFromString<EsiLocation>(body).name else null
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
    private data class EsiName(val id: Long, val name: String)

    @Serializable
    private data class EsiAssetName(@SerialName("item_id") val itemId: Long, val name: String)

    @Serializable
    private data class EsiLocation(val name: String)

    companion object {
        private const val LOOKUP_BATCH_SIZE = 1000
        private const val MAX_TYPE_LOOKUPS_PER_BATCH = 8
        private const val STRUCTURE_ID_MIN = 1_000_000_000_000L
    }
}

private class EsiRequestFailed(endpoint: String, status: Int, body: String) :
    Exception("ESI $endpoint answered $status: $body")