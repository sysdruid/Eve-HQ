package eve.hq.core.production

import eve.hq.core.attempt
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.character.EveCharacter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
    private val clock: Clock,
    private val freshFor: Duration = Duration.ofMinutes(5),
) {
    private class Remembered<T>(val value: T, val at: Instant)

    private val traitsOf = ConcurrentHashMap<Long, Remembered<CharacterTraits>>()
    private val timeEfficiencyOf = ConcurrentHashMap<Long, Remembered<Map<TypeId, Int>>>()
    private val structureOf = ConcurrentHashMap<Long, Remembered<Long?>>()

    private fun <T> Remembered<T>?.whileFresh(): Remembered<T>? = this?.takeIf { it.at.plus(freshFor).isAfter(clock.instant()) }

    suspend fun profile(characters: List<EveCharacter>, facilityIds: Set<Long>): RunTimeProfile {
        val failures = mutableListOf<TimeInputFailure>()
        fun report(character: EveCharacter, error: Throwable) {
            failures += TimeInputFailure(character.name, error.message ?: "Unable to read the character")
        }

        // Reading is remembered for a few minutes, so switching projects does not ask ESI about every character again.
        // Only what was read is remembered: a character that failed is tried again next time.
        val traits = characters.mapNotNull { character ->
            traitsOf[character.id].whileFresh()?.value
                ?: attempt { CharacterTraits(skills.levels(character), implants.implants(character)) }
                    .onSuccess { traitsOf[character.id] = Remembered(it, clock.instant()) }
                    .onFailure { report(character, it) }
                    .getOrNull()
        }

        // Several characters may own a copy of a blueprint; the planner may use the best, so the best counts.
        val bestTe = mutableMapOf<TypeId, Int>()
        characters.forEach { character ->
            val owned = timeEfficiencyOf[character.id].whileFresh()?.value
                ?: attempt { blueprints.load(character).groupBy({ it.type.id }, { it.timeEfficiency }).mapValues { (_, te) -> te.max() } }
                    .onSuccess { timeEfficiencyOf[character.id] = Remembered(it, clock.instant()) }
                    .onFailure { report(character, it) }
                    .getOrNull()
            owned?.forEach { (blueprint, te) -> bestTe.merge(blueprint, te, ::maxOf) }
        }

        // Docking rights are per character, so ask each until one can see the structure; nobody seeing it is remembered too.
        val structures = facilityIds.mapNotNull { facility ->
            val known = structureOf[facility].whileFresh()
            if (known != null) {
                known.value
            } else {
                val type = characters.firstNotNullOfOrNull { character -> attempt { structureTypes.typeOf(character, facility) }.getOrNull() }
                structureOf[facility] = Remembered(type, clock.instant())
                type
            }
        }.toSet()

        return RunTimeProfile(RunTimes(rules, traits, structures) { bestTe[it] ?: 0 }, failures)
    }
}
