package eve.hq.core.asset

import eve.hq.core.character.EveCharacter
import java.time.Clock
import java.time.Instant

data class AssetType(val id: Long, val name: String?)

data class AssetLocation(val id: Long, val name: String?)

data class AssetItem(
    val itemId: Long,
    val type: AssetType,
    val customName: String?,
    val quantity: Int,
    val location: AssetLocation,
)

fun interface AssetSource {
    suspend fun load(character: EveCharacter): List<AssetItem>

    fun cachedSnapshot(characterId: Long): AssetSnapshot? = null
}

data class AssetRow(
    val characterName: String,
    val stationName: String,
    val containerPath: String,
    val typeName: String,
    val quantity: Int,
)

data class AssetLoadFailure(val characterId: Long, val characterName: String, val message: String)

data class AssetSnapshotInfo(val characterId: Long, val characterName: String, val updatedAt: Instant)

class AssetAccessDenied(message: String) : Exception(message)

data class AssetListing(
    val items: List<AssetRow>,
    val failures: List<AssetLoadFailure>,
    val snapshots: List<AssetSnapshotInfo> = emptyList(),
)

class ListAssets(private val service: AssetService) {
    /** Convenience for callers with a plain source; production shares one [AssetService] between all consumers. */
    constructor(source: AssetSource, clock: Clock = Clock.systemUTC()) : this(AssetService(source, clock))

    suspend operator fun invoke(characters: List<EveCharacter>, query: String = "", refresh: Boolean = false): AssetListing =
        listing(service.assets(characters, refresh = refresh), query)

    fun cached(characters: List<EveCharacter>, query: String = ""): AssetListing =
        listing(service.savedAssets(characters), query)

    private fun listing(owned: OwnedAssets, query: String): AssetListing {
        val rows = owned.items.map { asset ->
            AssetRow(
                characterName = asset.characterName,
                stationName = asset.station.name ?: "Location ${asset.station.id}",
                containerPath = asset.containers.joinToString(" > "),
                typeName = asset.type.name ?: "Unknown type (${asset.type.id})",
                quantity = asset.quantity,
            )
        }.sortedWith(
            compareBy<AssetRow> { it.characterName.lowercase() }
                .thenBy { it.stationName.lowercase() }
                .thenBy { it.containerPath.lowercase() }
                .thenBy { it.typeName.lowercase() },
        )
        return AssetListing(SearchAssets()(rows, query), owned.failures, owned.snapshots)
    }
}

class SearchAssets {
    operator fun invoke(items: List<AssetRow>, query: String): List<AssetRow> = items.filter { row ->
        query.isBlank() || listOf(row.characterName, row.stationName, row.containerPath, row.typeName)
            .any { it.contains(query, ignoreCase = true) }
    }
}