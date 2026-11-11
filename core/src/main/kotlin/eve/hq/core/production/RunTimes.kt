package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** The dogma attributes that shorten a job, by their id in the SDE. */
object TimeAttributes {
    const val MANUFACTURING_TIME_BONUS = 440
    const val ADVANCED_INDUSTRY_BONUS = 1961
    const val TIME_PER_LEVEL = 1982
    const val REACTION_TIME_BONUS = 2660
    const val ENGINEERING_STRUCTURE_TIME = 2602
    const val REACTION_STRUCTURE_TIME = 2721
}

/** What the SDE holds about shortening jobs. */
interface TimeRules {
    /** An attribute as the SDE gives it: -4.0 is 4% off per level (or per implant), 0.85 a structure's multiplier. */
    fun bonus(type: TypeId, attribute: Int): Double?

    /** The skills a blueprint requires for an activity, by skill type id and level. */
    fun requiredSkills(blueprint: TypeId, activity: IndustryActivity): Map<TypeId, Int>
}

/** What one character brings to a job. */
data class CharacterTraits(val skills: Map<TypeId, Int>, val implants: Set<TypeId>)

/**
 * Works out how long jobs take. A job is run by one character, so the best whole character is taken (optimistic: the owner
 * runs jobs with their strongest one), never the best of each skill across different people. The structure is the best of
 * [structures] that has a bonus for the activity.
 */
class RunTimes(
    private val rules: TimeRules,
    private val traits: List<CharacterTraits>,
    private val structures: Set<TypeId> = emptySet(),
    /** The best time efficiency the owner has for a blueprint type, in percent. */
    private val blueprintTe: (TypeId) -> Int = { 0 },
) {
    /** What one character brings to every job of an activity, whatever the blueprint: worked out once. */
    private class Everywhere(val skills: List<Double>, val implants: List<Double>)

    private val everywhere = ConcurrentHashMap<IndustryActivity, List<Everywhere>>()
    private val remembered = ConcurrentHashMap<Triple<TypeId, IndustryActivity, Int>, TimeModifiers>()

    fun modifiers(recipe: Recipe, blueprintTe: Int = this.blueprintTe(recipe.blueprint)): TimeModifiers =
        // Planning asks about the same recipe several times, and each answer would loop over every skill of every character.
        remembered.getOrPut(Triple(recipe.blueprint, recipe.activity, blueprintTe)) { work(recipe, blueprintTe) }

    private fun work(recipe: Recipe, blueprintTe: Int): TimeModifiers {
        val structure = structureReduction(recipe.activity)
        val required = rules.requiredSkills(recipe.blueprint, recipe.activity).keys
        val shared = everywhere.getOrPut(recipe.activity) {
            traits.map { Everywhere(everywhereSkillReductions(it, recipe.activity), implantReductions(it, recipe.activity)) }
        }
        return traits.indices
            .map { i ->
                TimeModifiers(blueprintTe, shared[i].skills + requiredSkillReductions(traits[i], required), shared[i].implants, structure)
            }
            .minByOrNull { it.factor }
            ?: TimeModifiers(blueprintTe, structure = structure)
    }

    /** Industry, Advanced Industry and Reactions shorten every job of their kind. */
    private fun everywhereSkillReductions(character: CharacterTraits, activity: IndustryActivity): List<Double> =
        character.skills.flatMap { (skill, level) ->
            SKILL_ATTRIBUTES.getValue(activity).mapNotNull { attribute -> rules.bonus(skill, attribute)?.let { perLevel(it, level) } }
        }

    /** An engineering skill shortens only the jobs of blueprints that require it; the character's own level counts. */
    private fun requiredSkillReductions(character: CharacterTraits, required: Set<TypeId>): List<Double> =
        required.mapNotNull { skill ->
            val level = character.skills[skill] ?: return@mapNotNull null
            rules.bonus(skill, TimeAttributes.TIME_PER_LEVEL)?.let { perLevel(it, level) }
        }

    private fun implantReductions(character: CharacterTraits, activity: IndustryActivity): List<Double> =
        character.implants.mapNotNull { implant -> rules.bonus(implant, IMPLANT_ATTRIBUTE.getValue(activity))?.let { perLevel(it, 1) } }

    /** The best multiplier among the structures that have one for the activity, as a reduction. */
    private fun structureReduction(activity: IndustryActivity): Double {
        val best = structures.mapNotNull { rules.bonus(it, STRUCTURE_ATTRIBUTE.getValue(activity)) }.filter { it < 1.0 }.minOrNull()
            ?: return 0.0
        return (BigDecimal.ONE - BigDecimal(best.toString())).toDouble()
    }

    // The SDE writes a reduction as a negative percent: -4.0 is 4% off.
    private fun perLevel(percent: Double, level: Int): Double =
        // movePointLeft is exact; a plain BigDecimal division would round 0.15 to the scale of 15.0.
        (BigDecimal(percent.toString()).negate() * BigDecimal(level)).movePointLeft(2).toDouble()

    fun seconds(recipe: Recipe, runs: Long, blueprintTe: Int = this.blueprintTe(recipe.blueprint)): Long =
        modifiers(recipe, blueprintTe).seconds(recipe.secondsPerRun, runs)

    private companion object {
        val SKILL_ATTRIBUTES = mapOf(
            IndustryActivity.Manufacturing to listOf(TimeAttributes.MANUFACTURING_TIME_BONUS, TimeAttributes.ADVANCED_INDUSTRY_BONUS),
            IndustryActivity.Reaction to listOf(TimeAttributes.REACTION_TIME_BONUS),
        )
        val IMPLANT_ATTRIBUTE = mapOf(
            IndustryActivity.Manufacturing to TimeAttributes.MANUFACTURING_TIME_BONUS,
            IndustryActivity.Reaction to TimeAttributes.REACTION_TIME_BONUS,
        )
        val STRUCTURE_ATTRIBUTE = mapOf(
            IndustryActivity.Manufacturing to TimeAttributes.ENGINEERING_STRUCTURE_TIME,
            IndustryActivity.Reaction to TimeAttributes.REACTION_STRUCTURE_TIME,
        )
    }
}
