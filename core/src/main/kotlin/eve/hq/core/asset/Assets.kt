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
        listing(service.assetsOf(characters, refresh), query)

    fun cached(characters: List<EveCharacter>, query: String = ""): AssetListing =
        listing(service.saved(characters).filter { it.snapshot != null }, query)

    private fun listing(results: List<CharacterAssets>, query: String): AssetListing {
        val rows = results.flatMap { result -> result.snapshot?.let { assetRows(result.character, it.items) }.orEmpty() }
        val failures = results.mapNotNull(CharacterAssets::failure)
        val snapshots = results.mapNotNull { result ->
            result.snapshot?.let { AssetSnapshotInfo(result.character.id, result.character.name, it.updatedAt) }
        }
        val sorted = rows.sortedWith(
            compareBy<AssetRow> { it.characterName.lowercase() }
                .thenBy { it.stationName.lowercase() }
                .thenBy { it.containerPath.lowercase() }
                .thenBy { it.typeName.lowercase() },
        )
        return AssetListing(SearchAssets()(sorted, query), failures, snapshots)
    }

    private fun assetRows(character: EveCharacter, assets: List<AssetItem>): List<AssetRow> {
        val assetsById = assets.associateBy(AssetItem::itemId)
        return assets.map { it.toAssetRow(character, assetsById) }
    }

    private fun AssetItem.toAssetRow(
        character: EveCharacter,
        assetsById: Map<Long, AssetItem>,
    ): AssetRow {
        val containers = mutableListOf<String>()
        val visited = mutableSetOf(itemId)
        var currentLocation = location
        while (true) {
            val parent = assetsById[currentLocation.id]?.takeIf { visited.add(it.itemId) } ?: break
            containers += parent.customName
                ?: parent.type.name
                ?: "Unknown type (${parent.type.id})"
            currentLocation = parent.location
        }
        return AssetRow(
            characterName = character.name,
            stationName = currentLocation.name ?: "Location ${currentLocation.id}",
            containerPath = containers.asReversed().joinToString(" > "),
            typeName = type.name ?: "Unknown type (${type.id})",
            quantity = quantity,
        )
    }
}

class SearchAssets {
    operator fun invoke(items: List<AssetRow>, query: String): List<AssetRow> = items.filter { row ->
        query.isBlank() || listOf(row.characterName, row.stationName, row.containerPath, row.typeName)
            .any { it.contains(query, ignoreCase = true) }
    }
}