package eve.hq.core.production

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.core.industry.IndustryActivity
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test

class RunTimeServiceTest {
    private val industry = 3380L
    private val advanced = 3388L
    private val bx802 = 27167L
    private val raitaru = 35825L
    private val photonBlueprint = 100L
    private val structureId = 1_035_466_617_946L
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
    private val photon = Recipe(1, 1, emptyMap(), blueprint = photonBlueprint, secondsPerRun = 75)

    private val rules = object : TimeRules {
        private val bonuses = mapOf((industry to 440) to -4.0, (advanced to 1961) to -3.0, (bx802 to 440) to -2.0, (raitaru to 2602) to 0.85)

        override fun bonus(type: TypeId, attribute: Int) = bonuses[type to attribute]

        override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity) = emptyMap<TypeId, Int>()
    }

    private val skills = mutableMapOf<Long, Result<Map<Long, Int>>>()
    private val implants = mutableMapOf<Long, Set<Long>>()
    private val owned = mutableMapOf<Long, Result<List<OwnedBlueprint>>>()
    private val structureTypes = mutableMapOf<Pair<Long, Long>, Long?>() // (character, facility) -> type

    private val start = Instant.parse("2026-11-11T12:00:00Z")
    private var now = start
    private val clock = object : java.time.Clock() {
        override fun getZone() = java.time.ZoneId.of("UTC")

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }
    private var skillReads = 0
    private var implantReads = 0
    private var blueprintReads = 0
    private var structureReads = 0

    private val service = RunTimeService(
        rules,
        SkillSource { skillReads++; skills.getValue(it.id).getOrThrow() },
        ImplantSource { implantReads++; implants[it.id].orEmpty() },
        StructureTypes { character, facility -> structureReads++; structureTypes[character.id to facility] },
        BlueprintSource { blueprintReads++; owned.getValue(it.id).getOrThrow() },
        clock,
        java.time.Duration.ofMinutes(5),
    )

    private fun blueprint(te: Int, type: Long = photonBlueprint) = OwnedBlueprint(
        itemId = te.toLong(), type = AssetType(type, null), kind = BlueprintKind.Original, materialEfficiency = 0,
        timeEfficiency = te, remainingRuns = null, maxRuns = null, location = AssetLocation(1, null),
    )

    private fun profile(vararg characters: EveCharacter, facilities: Set<Long> = emptySet()) =
        runBlocking { service.profile(characters.toList(), facilities) }

    init {
        listOf(alice, bob).forEach { owned[it.id] = Result.success(emptyList()) }
    }

    @Test
    fun `each character's skills and implants are read, and the strongest whole character counts`() {
        skills[alice.id] = Result.success(mapOf(industry to 5, advanced to 5))
        skills[bob.id] = Result.success(mapOf(industry to 3))
        implants[bob.id] = setOf(bx802)

        val modifiers = profile(alice, bob).runTimes.modifiers(photon)

        assertThat(modifiers.skills).containsExactlyInAnyOrder(0.20, 0.15) // Alice
        assertThat(modifiers.implants).isEmpty()
    }

    @Test
    fun `a character that cannot be read is reported and the others still count`() {
        skills[alice.id] = Result.failure(LoginRequired("EVE denied skill access. Please use Add character."))
        skills[bob.id] = Result.success(mapOf(industry to 3))

        val profile = profile(alice, bob)

        assertThat(profile.failures).containsExactly(TimeInputFailure("Alice", "EVE denied skill access. Please use Add character."))
        assertThat(profile.runTimes.modifiers(photon).skills).containsExactly(0.12)
    }

    @Test
    fun `the structure type of a facility is asked of the characters until one can see it`() {
        skills[alice.id] = Result.success(emptyMap())
        skills[bob.id] = Result.success(emptyMap())
        structureTypes[bob.id to structureId] = raitaru // only Bob has docking rights

        val modifiers = profile(alice, bob, facilities = setOf(structureId, 60003760)).runTimes.modifiers(photon)

        assertThat(modifiers.structure).isEqualTo(0.15)
    }

    @Test
    fun `a facility nobody can see gives no structure bonus`() {
        skills[alice.id] = Result.success(emptyMap())

        assertThat(profile(alice, facilities = setOf(structureId)).runTimes.modifiers(photon).structure).isEqualTo(0.0)
    }

    @Test
    fun `the best time efficiency owned for a blueprint is used, whoever owns it`() {
        skills[alice.id] = Result.success(emptyMap())
        skills[bob.id] = Result.success(emptyMap())
        owned[alice.id] = Result.success(listOf(blueprint(te = 10), blueprint(te = 4, type = 999)))
        owned[bob.id] = Result.success(listOf(blueprint(te = 20)))

        val runTimes = profile(alice, bob).runTimes

        assertThat(runTimes.modifiers(photon).blueprintTe).isEqualTo(20)
        assertThat(runTimes.modifiers(photon.copy(blueprint = 999)).blueprintTe).isEqualTo(4)
        assertThat(runTimes.modifiers(photon.copy(blueprint = 12345)).blueprintTe).isEqualTo(0) // none owned
    }

    @Test
    fun `blueprints that cannot be read count as no time efficiency and are reported`() {
        skills[alice.id] = Result.success(emptyMap())
        owned[alice.id] = Result.failure(java.io.IOException("ESI down"))

        val profile = profile(alice)

        assertThat(profile.failures).containsExactly(TimeInputFailure("Alice", "ESI down"))
        assertThat(profile.runTimes.modifiers(photon).blueprintTe).isZero()
    }

    @Test
    fun `what was read is reused within the fresh window and read again after it`() {
        skills[alice.id] = Result.success(mapOf(industry to 5))
        structureTypes[alice.id to structureId] = raitaru

        profile(alice, facilities = setOf(structureId))
        now = start.plusSeconds(299)
        profile(alice, facilities = setOf(structureId))

        assertThat(listOf(skillReads, implantReads, blueprintReads, structureReads)).containsOnly(1)

        now = start.plusSeconds(300)
        profile(alice, facilities = setOf(structureId))

        assertThat(listOf(skillReads, implantReads, blueprintReads, structureReads)).containsOnly(2)
    }

    @Test
    fun `a structure nobody could see is not asked about again within the window`() {
        skills[alice.id] = Result.success(emptyMap())

        profile(alice, facilities = setOf(structureId))
        profile(alice, facilities = setOf(structureId))

        assertThat(structureReads).isEqualTo(1)
    }

    @Test
    fun `a character that failed is read again at the next profile, not remembered as failed`() {
        skills[alice.id] = Result.failure(LoginRequired("denied"))
        profile(alice)

        skills[alice.id] = Result.success(mapOf(industry to 5))
        val profile = profile(alice)

        assertThat(profile.failures).isEmpty()
        assertThat(profile.runTimes.modifiers(photon).skills).containsExactly(0.20)
    }
}
