package eve.hq.core.asset

import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.CancellationException

data class AssetItem(
    val itemId: Long,
    val typeId: Long,
    val quantity: Int,
    val locationId: Long,
)

data class AssetInventory(
    val items: List<AssetItem>,
    val typeNames: Map<Long, String>,
    val locationNames: Map<Long, String>,
    val itemNames: Map<Long, String> = emptyMap(),
)

fun interface AssetSource {
    suspend fun load(character: EveCharacter): AssetInventory
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
        val rows = mutableListOf<AssetRow>()
        val failures = mutableListOf<AssetLoadFailure>()
        for (character in characters) {
            val inventory = try {
                source.load(character)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += AssetLoadFailure(character.id, character.name, e.message ?: "Unable to load assets")
                continue
            }
            val assetsById = inventory.items.associateBy(AssetItem::itemId)
            inventory.items.forEach { item ->
                val containers = mutableListOf<String>()
                val visited = mutableSetOf(item.itemId)
                var locationId = item.locationId
                while (true) {
                    val parent = assetsById[locationId]?.takeIf { visited.add(it.itemId) } ?: break
                    containers += inventory.itemNames[parent.itemId]
                        ?: inventory.typeNames[parent.typeId]
                        ?: "Unknown type (${parent.typeId})"
                    locationId = parent.locationId
                }
                rows += AssetRow(
                    characterName = character.name,
                    stationName = inventory.locationNames[locationId] ?: "Location $locationId",
                    containerPath = containers.asReversed().joinToString(" > "),
                    typeName = inventory.typeNames[item.typeId] ?: "Unknown type (${item.typeId})",
                    quantity = item.quantity,
                )
            }
        }
        val sorted = rows.sortedWith(
            compareBy<AssetRow> { it.characterName.lowercase() }
                .thenBy { it.stationName.lowercase() }
                .thenBy { it.containerPath.lowercase() }
                .thenBy { it.typeName.lowercase() },
        )
        return AssetListing(SearchAssets()(sorted, query), failures)
    }
}

class SearchAssets {
    operator fun invoke(items: List<AssetRow>, query: String): List<AssetRow> = items.filter { row ->
        query.isBlank() || listOf(row.characterName, row.stationName, row.containerPath, row.typeName)
            .any { it.contains(query, ignoreCase = true) }
    }
}