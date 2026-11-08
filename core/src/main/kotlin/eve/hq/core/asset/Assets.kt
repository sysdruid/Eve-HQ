package eve.hq.core.asset

import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.CancellationException
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

class ListAssets(private val source: AssetSource) {
    suspend operator fun invoke(characters: List<EveCharacter>, query: String = ""): AssetListing {
        val characterResults = characters.map { characterAssets(it) }
        return listing(characterResults, query)
    }

    fun cached(characters: List<EveCharacter>, query: String = ""): AssetListing {
        val characterResults = characters.mapNotNull { character ->
            source.cachedSnapshot(character.id)?.let { snapshot ->
                CharacterAssets(
                    rows = assetRows(character, snapshot.items),
                    snapshot = AssetSnapshotInfo(character.id, character.name, snapshot.updatedAt),
                )
            }
        }
        return listing(characterResults, query)
    }

    private fun listing(characterResults: List<CharacterAssets>, query: String): AssetListing {
        val rows = characterResults.flatMap(CharacterAssets::rows)
        val failures = characterResults.mapNotNull(CharacterAssets::failure)
        val snapshots = characterResults.mapNotNull(CharacterAssets::snapshot)
        val sorted = rows.sortedWith(
            compareBy<AssetRow> { it.characterName.lowercase() }
                .thenBy { it.stationName.lowercase() }
                .thenBy { it.containerPath.lowercase() }
                .thenBy { it.typeName.lowercase() },
        )
        return AssetListing(SearchAssets()(sorted, query), failures, snapshots)
    }

    private suspend fun characterAssets(character: EveCharacter): CharacterAssets {
        val result = loadAssets(character)
        val cached = source.cachedSnapshot(character.id)
        val failure = result.exceptionOrNull()?.let { error ->
            AssetLoadFailure(
                character.id,
                character.name,
                error.message ?: "Unable to load assets",
            )
        }
        return CharacterAssets(
            rows = assetRows(character, result.getOrElse { cached?.items.orEmpty() }),
            failure = failure,
            snapshot = cached?.let { AssetSnapshotInfo(character.id, character.name, it.updatedAt) },
        )
    }

    private fun assetRows(character: EveCharacter, assets: List<AssetItem>): List<AssetRow> {
        val assetsById = assets.associateBy(AssetItem::itemId)
        return assets.map { it.toAssetRow(character, assetsById) }
    }

    private suspend fun loadAssets(character: EveCharacter): Result<List<AssetItem>> = try {
        Result.success(source.load(character))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
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

    private data class CharacterAssets(
        val rows: List<AssetRow> = emptyList(),
        val failure: AssetLoadFailure? = null,
        val snapshot: AssetSnapshotInfo? = null,
    )
}

class SearchAssets {
    operator fun invoke(items: List<AssetRow>, query: String): List<AssetRow> = items.filter { row ->
        query.isBlank() || listOf(row.characterName, row.stationName, row.containerPath, row.typeName)
            .any { it.contains(query, ignoreCase = true) }
    }
}