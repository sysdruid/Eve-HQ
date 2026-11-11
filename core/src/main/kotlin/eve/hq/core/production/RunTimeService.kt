package eve.hq.core.production

import eve.hq.core.attempt
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.character.EveCharacter

/** A character whose skills, implants or blueprints could not be read, so it does not count towards the run times. */
data class TimeInputFailure(val characterName: String, val message: String)

data class RunTimeProfile(val runTimes: RunTimes, val failures: List<TimeInputFailure>)

/** Gathers what run times depend on for a set of characters and the facilities their jobs run in. */
class RunTimeService(
    private val rules: TimeRules,
    private val skills: SkillSource,
    private val implants: ImplantSource,
    private val structureTypes: StructureTypes,
    private val blueprints: BlueprintSource,
) {
    suspend fun profile(characters: List<EveCharacter>, facilityIds: Set<Long>): RunTimeProfile {
        val failures = mutableListOf<TimeInputFailure>()
        fun report(character: EveCharacter, error: Throwable) {
            failures += TimeInputFailure(character.name, error.message ?: "Unable to read the character")
        }

        val traits = characters.mapNotNull { character ->
            attempt { CharacterTraits(skills.levels(character), implants.implants(character)) }
                .onFailure { report(character, it) }
                .getOrNull()
        }

        // Several characters may own a copy of a blueprint; the planner may use the best, so the best counts.
        val bestTe = mutableMapOf<TypeId, Int>()
        characters.forEach { character ->
            attempt { blueprints.load(character) }
                .onSuccess { owned -> owned.forEach { bestTe.merge(it.type.id, it.timeEfficiency, ::maxOf) } }
                .onFailure { report(character, it) }
        }

        // Docking rights are per character, so ask each until one can see the structure.
        val structures = facilityIds.mapNotNull { facility ->
            characters.firstNotNullOfOrNull { character -> attempt { structureTypes.typeOf(character, facility) }.getOrNull() }
        }.toSet()

        return RunTimeProfile(RunTimes(rules, traits, structures) { bestTe[it] ?: 0 }, failures)
    }
}
