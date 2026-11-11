package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity
import org.assertj.core.api.Assertions.assertThat
import java.time.Duration
import kotlin.test.Test

class RunTimesTest {
    private val industry = 3380L
    private val advanced = 3388L
    private val electronic = 11453L
    private val mechanical = 11452L
    private val reactionsSkill = 45746L
    private val bx802 = 27167L
    private val raitaru = 35825L
    private val azbel = 35826L
    private val athanor = 35835L
    private val tatara = 35836L
    private val photonBlueprint = 100L
    private val carbonideFormula = 200L

    private val rules = object : TimeRules {
        private val bonuses = mapOf(
            (industry to 440) to -4.0, (advanced to 1961) to -3.0, (electronic to 1982) to -1.0, (mechanical to 1982) to -1.0,
            (reactionsSkill to 2660) to -4.0, (bx802 to 440) to -2.0,
            (raitaru to 2602) to 0.85, (azbel to 2602) to 0.80, (tatara to 2721) to 0.75,
        )
        private val required = mapOf(
            (photonBlueprint to IndustryActivity.Manufacturing) to mapOf(industry to 1, electronic to 3),
            (carbonideFormula to IndustryActivity.Reaction) to mapOf(reactionsSkill to 3),
        )

        override fun bonus(type: TypeId, attribute: Int) = bonuses[type to attribute]

        override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity) = required[blueprint to activity].orEmpty()
    }

    private val photon = Recipe(1, 1, emptyMap(), blueprint = photonBlueprint, secondsPerRun = 75)
    private val carbonide = Recipe(2, 1, emptyMap(), blueprint = carbonideFormula, secondsPerRun = 10_000, activity = IndustryActivity.Reaction)

    private val overseer = CharacterTraits(
        skills = mapOf(industry to 5, advanced to 5, electronic to 4, mechanical to 5),
        implants = setOf(bx802),
    )

    // The game: 21,772 runs of Photon Microprocessor at a Raitaru, 8d 5h 19m, "-36%" for skills and implants.
    @Test
    fun `a real job from the game comes out the same from the SDE numbers and the character's skills`() {
        val seconds = RunTimes(rules, listOf(overseer), setOf(raitaru)).seconds(photon, runs = 21_772, blueprintTe = 20)

        val inGame = Duration.ofDays(8).plusHours(5).plusMinutes(19).seconds
        assertThat(seconds).isBetween(inGame, inGame + 59)
    }

    @Test
    fun `only the engineering skills the blueprint requires shorten the job`() {
        // Mechanical Engineering V is no use for a blueprint that does not require it; Electronic Engineering IV is.
        val modifiers = RunTimes(rules, listOf(overseer)).modifiers(photon)

        assertThat(modifiers.skills).containsExactlyInAnyOrder(0.20, 0.15, 0.04)
        assertThat(modifiers.implants).containsExactly(0.02)
    }

    @Test
    fun `the strongest whole character is taken, not the best of each skill across characters`() {
        val broad = CharacterTraits(mapOf(industry to 5, advanced to 5), emptySet())
        val specialist = CharacterTraits(mapOf(industry to 3, advanced to 1, electronic to 5), setOf(bx802))

        val modifiers = RunTimes(rules, listOf(specialist, broad)).modifiers(photon)

        // broad: 0.8 x 0.85 = 0.68; specialist: 0.88 x 0.97 x 0.95 x 0.98 = 0.795. Broad is faster, and nothing is mixed in.
        assertThat(modifiers.skills).containsExactlyInAnyOrder(0.20, 0.15)
        assertThat(modifiers.implants).isEmpty()
    }

    @Test
    fun `without characters only the blueprint and the structure shorten a job`() {
        val modifiers = RunTimes(rules, emptyList(), setOf(raitaru)).modifiers(photon, blueprintTe = 20)

        assertThat(modifiers).isEqualTo(TimeModifiers(blueprintTe = 20, structure = 0.15))
    }

    @Test
    fun `a reaction uses the Reactions skill and the reaction structure bonus, not the manufacturing ones`() {
        val modifiers = RunTimes(rules, listOf(overseer.copy(skills = overseer.skills + (reactionsSkill to 5))), setOf(raitaru, tatara))
            .modifiers(carbonide)

        assertThat(modifiers.skills).containsExactly(0.20) // Reactions V; Industry and Advanced Industry do not apply
        assertThat(modifiers.implants).isEmpty() // the manufacturing implant does not apply to reactions
        assertThat(modifiers.structure).isEqualTo(0.25) // Tatara; the Raitaru has no reaction bonus
    }

    @Test
    fun `the best structure with a bonus for the activity is used, one without a bonus is ignored`() {
        val manufacturing = RunTimes(rules, emptyList(), setOf(raitaru, azbel, athanor)).modifiers(photon)
        val reaction = RunTimes(rules, emptyList(), setOf(raitaru, athanor)).modifiers(carbonide)

        assertThat(manufacturing.structure).isEqualTo(0.20) // Azbel beats Raitaru; the Athanor has no manufacturing bonus
        assertThat(reaction.structure).isEqualTo(0.0) // the Athanor has no reaction time bonus either
    }

    @Test
    fun `skill levels scale the reduction`() {
        val trainedLess = CharacterTraits(mapOf(industry to 3), emptySet())

        assertThat(RunTimes(rules, listOf(trainedLess)).modifiers(photon).skills).containsExactly(0.12)
    }

    private class CountingRules(private val delegate: TimeRules) : TimeRules {
        var bonusLookups = 0
        var requiredLookups = 0

        override fun bonus(type: TypeId, attribute: Int): Double? {
            bonusLookups++
            return delegate.bonus(type, attribute)
        }

        override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity): Map<TypeId, Int> {
            requiredLookups++
            return delegate.requiredSkills(blueprint, activity)
        }
    }

    @Test
    fun `a recipe's modifiers are worked out once, and a character's own skills once per kind of job`() {
        val counting = CountingRules(rules)
        val runTimes = RunTimes(counting, listOf(overseer, overseer.copy(implants = emptySet())), setOf(raitaru))

        runTimes.modifiers(photon, blueprintTe = 20)
        val afterFirst = counting.bonusLookups
        runTimes.modifiers(photon, blueprintTe = 20)
        val afterRepeat = counting.bonusLookups
        runTimes.modifiers(photon.copy(blueprint = 101L), blueprintTe = 20) // another blueprint of the same activity
        val afterOther = counting.bonusLookups

        assertThat(afterRepeat).isEqualTo(afterFirst) // the same recipe is not asked about again
        // Another blueprint only costs its own required skills (here none match), never the characters' whole skill lists again.
        assertThat(afterOther - afterRepeat).isLessThanOrEqualTo(2 * 2)
        assertThat(counting.requiredLookups).isEqualTo(2)
    }
}
