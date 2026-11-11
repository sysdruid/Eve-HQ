package eve.hq.core.production

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginRequired
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import eve.hq.core.industry.ListJobs
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

/** A plan's job times come from the owner's skills, implants, structures and blueprints, not from a guessed multiplier. */
class PlanProjectRunTimesTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val shuttle = 11134L
    private val shuttleBlueprint = 11135L
    private val tritanium = 34L
    private val industry = 3380L
    private val raitaru = 35825L
    private val raitaruId = 1_035_466_617_946L

    private val catalog = FakeItemCatalog(names = mapOf(shuttle to "Amarr Shuttle", tritanium to "Tritanium"))
    private val recipes = RecipeBook { product ->
        Recipe(shuttle, 1, mapOf(tritanium to 2778), blueprint = shuttleBlueprint, secondsPerRun = 6000).takeIf { product == shuttle }
    }
    private val rules = object : TimeRules {
        private val bonuses = mapOf((industry to 440) to -4.0, (raitaru to 2602) to 0.85)

        override fun bonus(type: TypeId, attribute: Int) = bonuses[type to attribute]

        override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity) = emptyMap<TypeId, Int>()
    }

    private var skills: Result<Map<Long, Int>> = Result.success(mapOf(industry to 5))
    private var running: List<IndustryJob> = emptyList()
    private var blueprints: List<OwnedBlueprint> = emptyList()
    private var structureAt: Map<Long, Long> = emptyMap()

    private val planProject = PlanProject(
        Projects(Items(catalog), recipes),
        recipes,
        AssetService(AssetSource { emptyList() }, clock),
        ListJobs(JobSource { running }, clock),
        clock,
        RunTimeService(
            rules,
            SkillSource { skills.getOrThrow() },
            ImplantSource { emptySet() },
            StructureTypes { _, facility -> structureAt[facility] },
            BlueprintSource { blueprints },
            clock,
        ),
    )

    private fun plan() = runBlocking { planProject(ProjectId.AmarrShuttles, listOf(alice)) }

    private fun jobAt(facility: Long) = IndustryJob(
        jobId = 1, activity = IndustryActivity.Manufacturing, blueprintId = 1, blueprint = JobItem(1, "Rifter Blueprint"),
        product = JobItem(587, "Rifter"), runs = 1, startedAt = now.minusSeconds(60), endsAt = now.plus(Duration.ofHours(1)),
        status = JobStatus.Active, facility = JobFacility(facility, "Somewhere"),
    )

    @Test
    fun `a job takes the time the character's skills give it`() {
        val job = plan().plan.jobs.single()

        // Industry V is 20% off: 1000 runs x 6000 s x 0.8
        assertThat(job.totalSeconds).isEqualTo(4_800_000)
        assertThat(job.secondsPerRun).isEqualTo(4_800)
    }

    @Test
    fun `the structures where running jobs are found add their bonus`() {
        running = listOf(jobAt(raitaruId))
        structureAt = mapOf(raitaruId to raitaru)

        assertThat(plan().plan.jobs.single().totalSeconds).isEqualTo(4_080_000) // x 0.8 x 0.85
    }

    @Test
    fun `the best time efficiency owned for the blueprint counts`() {
        blueprints = listOf(
            OwnedBlueprint(1, AssetType(shuttleBlueprint, null), BlueprintKind.Original, 0, 20, null, null, AssetLocation(1, null)),
        )

        assertThat(plan().plan.jobs.single().totalSeconds).isEqualTo(3_840_000) // x 0.8 x 0.8
    }

    @Test
    fun `skills that cannot be read are reported and the plan falls back to base time`() {
        skills = Result.failure(LoginRequired("EVE denied skill access. Please use Add character."))

        val result = plan()

        assertThat(result.timeFailures).containsExactly(TimeInputFailure("Alice", "EVE denied skill access. Please use Add character."))
        assertThat(result.plan.jobs.single().totalSeconds).isEqualTo(6_000_000)
    }
}
