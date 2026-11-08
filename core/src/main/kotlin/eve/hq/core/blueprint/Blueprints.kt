package eve.hq.core.blueprint

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.character.EveCharacter

enum class BlueprintKind { Original, Copy }

data class OwnedBlueprint(
    val itemId: Long,
    val type: AssetType,
    val kind: BlueprintKind,
    val materialEfficiency: Int,
    val timeEfficiency: Int,
    /** Runs left on a copy; originals have none. */
    val remainingRuns: Int?,
    /** Maximum production limit from static data; not shown, kept for planning. */
    val maxRuns: Int?,
    val location: AssetLocation,
)

fun interface BlueprintSource {
    suspend fun load(character: EveCharacter): List<OwnedBlueprint>
}
