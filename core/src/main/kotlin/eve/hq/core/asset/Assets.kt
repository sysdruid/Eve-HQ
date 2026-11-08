package eve.hq.core.asset

import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.CancellationException

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
}

data class AssetRow(
    val characterName: String,
    val stationName: String,
    val containerPath: String,
    val typeName: String,
    val quantity: Int,
)

data class AssetLoadFailure(val characterId: Long, val characterName: String, val message: String)

class AssetAccessDenied(message: String) : Exception(message)

data class AssetListing(val items: List<AssetRow>, val failures: List<AssetLoadFailure>)

class ListAssets(private val source: AssetSource) {
    suspend operator fun invoke(characters: List<EveCharacter>, query: String = ""): AssetListing {
        val characterResults = characters.map { characterAssets(it) }
        val rows = characterResults.flatMap(CharacterAssets::rows)
        val failures = characterResults.mapNotNull(CharacterAssets::failure)
        val sorted = rows.sortedWith(
            compareBy<AssetRow> { it.characterName.lowercase() }
                .thenBy { it.stationName.lowercase() }
                .thenBy { it.containerPath.lowercase() }
                .thenBy { it.typeName.lowercase() },
        )
        return AssetListing(SearchAssets()(sorted, query), failures)
    }

    private suspend fun characterAssets(character: EveCharacter): CharacterAssets {
        val assets = loadAssets(character).getOrElse { error ->
            return CharacterAssets(
                failure = AssetLoadFailure(
                    character.id,
                    character.name,
                    error.message ?: "Unable to load assets",
                ),
            )
        }
        val assetsById = assets.associateBy(AssetItem::itemId)
        return CharacterAssets(rows = assets.map { it.toAssetRow(character, assetsById) })
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
    )
}

class SearchAssets {
    operator fun invoke(items: List<AssetRow>, query: String): List<AssetRow> = items.filter { row ->
        query.isBlank() || listOf(row.characterName, row.stationName, row.containerPath, row.typeName)
            .any { it.contains(query, ignoreCase = true) }
    }
}