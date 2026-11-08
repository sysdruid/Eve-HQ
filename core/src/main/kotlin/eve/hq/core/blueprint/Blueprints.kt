package eve.hq.core.blueprint

import eve.hq.core.asset.AssetLocation
import eve.hq.core.attempt
import eve.hq.core.asset.AssetType
import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobSource
import java.time.Instant

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

data class BlueprintUse(val activity: IndustryActivity, val endsAt: Instant)

data class BlueprintRow(
    val itemId: Long,
    val characterName: String,
    val blueprintName: String,
    val kind: BlueprintKind,
    val materialEfficiency: Int,
    val timeEfficiency: Int,
    val remainingRuns: Int?,
    val locationName: String,
    val use: BlueprintUse?,
)

data class BlueprintLoadFailure(val characterId: Long, val characterName: String, val message: String)

data class BlueprintListing(val rows: List<BlueprintRow>, val failures: List<BlueprintLoadFailure>)

class ListBlueprints(private val blueprints: BlueprintSource, private val jobs: JobSource) {
    suspend operator fun invoke(characters: List<EveCharacter>): BlueprintListing {
        val results = characters.map { character -> loadCharacter(character) }
        val rows = results.flatMap(CharacterBlueprints::rows)
            .sortedWith(compareBy<BlueprintRow> { it.characterName.lowercase() }.thenBy { it.blueprintName.lowercase() })
        return BlueprintListing(rows, results.flatMap(CharacterBlueprints::failures))
    }

    private suspend fun loadCharacter(character: EveCharacter): CharacterBlueprints {
        val owned = attempt { blueprints.load(character) }.getOrElse { error ->
            return CharacterBlueprints(failures = listOf(failure(character, error.message ?: "Unable to load blueprints")))
        }
        val running = attempt { jobs.load(character) }
        val inUse = running.getOrDefault(emptyList()).filter { it.status.ongoing }.associateBy { it.blueprintId }
        val warning = running.exceptionOrNull()?.let {
            failure(character, "In-use marks are unavailable: ${it.message ?: "unable to load jobs"}")
        }
        return CharacterBlueprints(
            rows = owned.map { it.toRow(character, inUse[it.itemId]) },
            failures = listOfNotNull(warning),
        )
    }

    private fun failure(character: EveCharacter, message: String) = BlueprintLoadFailure(character.id, character.name, message)

    private data class CharacterBlueprints(
        val rows: List<BlueprintRow> = emptyList(),
        val failures: List<BlueprintLoadFailure> = emptyList(),
    )

    private fun OwnedBlueprint.toRow(character: EveCharacter, job: IndustryJob?) = BlueprintRow(
        itemId = itemId,
        characterName = character.name,
        blueprintName = type.name ?: "Unknown type (${type.id})",
        kind = kind,
        materialEfficiency = materialEfficiency,
        timeEfficiency = timeEfficiency,
        remainingRuns = remainingRuns,
        locationName = location.name ?: "Location ${location.id}",
        use = job?.let { BlueprintUse(it.activity, it.endsAt) },
    )
}

class SearchBlueprints {
    operator fun invoke(rows: List<BlueprintRow>, query: String, freeOnly: Boolean): List<BlueprintRow> = rows.filter { row ->
        (!freeOnly || row.use == null) &&
            (query.isBlank() || listOf(row.blueprintName, row.characterName, row.locationName).any { it.contains(query.trim(), ignoreCase = true) })
    }
}
