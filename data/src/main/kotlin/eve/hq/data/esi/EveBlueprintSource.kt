package eve.hq.data.esi

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintPageCache
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.CachedBlueprintPage
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.EveCharacter
import eve.hq.data.LocationNameCache
import eve.hq.data.StaticBlueprintRepository
import eve.hq.data.StaticTypeNameRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

class EveBlueprintSource(
    private val http: HttpClient,
    baseUrl: String = "https://esi.evetech.net/latest/",
    staticTypeNames: StaticTypeNameRepository? = null,
    private val staticBlueprints: StaticBlueprintRepository? = null,
    locationNames: LocationNameCache? = null,
    private val pageCache: BlueprintPageCache? = null,
    private val clock: Clock = Clock.systemUTC(),
) : BlueprintSource {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }
    private val names = EsiNames(http, baseUrl, staticTypeNames, locationNames, clock)

    override suspend fun load(character: EveCharacter): List<OwnedBlueprint> {
        val blueprints = loadPages(character)
        val typeNames = names.typeNames(blueprints.map(EsiBlueprint::typeId))
        val locationNames = blueprints.map(EsiBlueprint::locationId).distinct().associateWith { id ->
            names.stationOrStructureName(id, character.token.accessToken)
        }
        val maxRuns = maxRunsOf(blueprints)
        return blueprints.map { it.toOwned(typeNames, maxRuns, locationNames) }
    }

    override fun cached(character: EveCharacter): List<OwnedBlueprint>? {
        val first = pageCache?.findPage(character.id, 1) ?: return null
        val pages = (1..first.pageCount).map { pageCache.findPage(character.id, it) ?: return null }
        val blueprints = pages.flatMap { json.decodeFromString<List<EsiBlueprint>>(it.body) }
        val typeNames = names.cachedTypeNames(blueprints.map(EsiBlueprint::typeId))
        val locationNames = blueprints.map(EsiBlueprint::locationId).distinct().associateWith(names::cachedLocationName)
        val maxRuns = maxRunsOf(blueprints)
        return blueprints.map { it.toOwned(typeNames, maxRuns, locationNames) }
    }

    private fun maxRunsOf(blueprints: List<EsiBlueprint>) =
        staticBlueprints?.findMaxRuns(blueprints.map(EsiBlueprint::typeId)).orEmpty()

    private fun EsiBlueprint.toOwned(
        typeNames: Map<Long, String>,
        maxRuns: Map<Long, Int>,
        locationNames: Map<Long, String?>,
    ) = OwnedBlueprint(
        itemId = itemId,
        type = AssetType(typeId, typeNames[typeId]),
        kind = if (quantity == COPY) BlueprintKind.Copy else BlueprintKind.Original,
        materialEfficiency = materialEfficiency,
        timeEfficiency = timeEfficiency,
        remainingRuns = runs.takeIf { quantity == COPY },
        maxRuns = maxRuns[typeId],
        location = AssetLocation(locationId, locationNames[locationId]),
    )

    private suspend fun loadPages(character: EveCharacter): List<EsiBlueprint> {
        val pages = mutableListOf<CachedBlueprintPage>()
        var refetched = false
        var pageCount = 1
        var page = 1
        while (page <= pageCount) {
            val cached = pageCache?.findPage(character.id, page)
            val loaded = if (cached?.expiresAt?.isAfter(clock.instant()) == true) {
                cached
            } else {
                refetched = true
                fetchPage(character, page, cached)
            }
            if (page == 1) pageCount = loaded.pageCount
            pages += loaded
            page++
        }
        // Rewriting also drops cached pages beyond the current page count.
        if (refetched || pageCache?.findPage(character.id, pageCount + 1) != null) pageCache?.replace(character.id, pages)
        return pages.flatMap { json.decodeFromString<List<EsiBlueprint>>(it.body) }
    }

    private suspend fun fetchPage(character: EveCharacter, page: Int, cached: CachedBlueprintPage?): CachedBlueprintPage {
        val response = http.get("${baseUrl}characters/${character.id}/blueprints/") {
            bearerAuth(character.token.accessToken)
            parameter("page", page)
            cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        val pageCount = response.headers["X-Pages"]?.toIntOrNull()?.takeIf { it > 0 }
        return if (response.status == HttpStatusCode.NotModified && cached != null) {
            cached.copy(
                etag = response.headers[HttpHeaders.ETag] ?: cached.etag,
                expiresAt = response.expiresAt(),
                pageCount = pageCount ?: cached.pageCount,
            )
        } else {
            CachedBlueprintPage(
                page = page,
                body = response.bodyOrFail("blueprints", "blueprint"),
                etag = response.headers[HttpHeaders.ETag],
                expiresAt = response.expiresAt(),
                pageCount = pageCount ?: 1,
            )
        }
    }

    @Serializable
    private data class EsiBlueprint(
        @SerialName("item_id") val itemId: Long,
        @SerialName("type_id") val typeId: Long,
        @SerialName("location_id") val locationId: Long,
        @SerialName("material_efficiency") val materialEfficiency: Int,
        @SerialName("time_efficiency") val timeEfficiency: Int,
        val quantity: Int,
        val runs: Int,
    )

    private companion object {
        // ESI reports -1 for an original and -2 for a copy in `quantity`.
        const val COPY = -2
    }
}
