package eve.hq.data.esi

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.EveCharacter
import eve.hq.data.StaticBlueprintRepository
import eve.hq.data.StaticTypeNameRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class EveBlueprintSource(
    private val http: HttpClient,
    baseUrl: String = "https://esi.evetech.net/latest/",
    staticTypeNames: StaticTypeNameRepository? = null,
    private val staticBlueprints: StaticBlueprintRepository? = null,
) : BlueprintSource {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }
    private val names = EsiNames(http, baseUrl, staticTypeNames)

    override suspend fun load(character: EveCharacter): List<OwnedBlueprint> {
        val blueprints = loadPages(character)
        val typeIds = blueprints.map(EsiBlueprint::typeId)
        val typeNames = names.typeNames(typeIds)
        val maxRuns = staticBlueprints?.findMaxRuns(typeIds).orEmpty()
        val locationNames = blueprints.map(EsiBlueprint::locationId).distinct().associateWith { id ->
            names.stationOrStructureName(id, character.token.accessToken)
        }
        return blueprints.map { blueprint ->
            OwnedBlueprint(
                itemId = blueprint.itemId,
                type = AssetType(blueprint.typeId, typeNames[blueprint.typeId]),
                kind = if (blueprint.quantity == COPY) BlueprintKind.Copy else BlueprintKind.Original,
                materialEfficiency = blueprint.materialEfficiency,
                timeEfficiency = blueprint.timeEfficiency,
                remainingRuns = blueprint.runs.takeIf { blueprint.quantity == COPY },
                maxRuns = maxRuns[blueprint.typeId],
                location = AssetLocation(blueprint.locationId, locationNames[blueprint.locationId]),
            )
        }
    }

    private suspend fun loadPages(character: EveCharacter): List<EsiBlueprint> {
        val blueprints = mutableListOf<EsiBlueprint>()
        var pageCount = 1
        var page = 1
        while (page <= pageCount) {
            val response = http.get("${baseUrl}characters/${character.id}/blueprints/") {
                bearerAuth(character.token.accessToken)
                parameter("page", page)
            }
            if (page == 1) pageCount = response.headers["X-Pages"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            blueprints += json.decodeFromString<List<EsiBlueprint>>(response.bodyOrFail("blueprints", "blueprint"))
            page++
        }
        return blueprints
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
