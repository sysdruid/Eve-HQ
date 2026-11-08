package eve.hq.app.blueprints

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.blueprint.BlueprintKind
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.ListBlueprints
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.character.ListCharacters
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlueprintsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `loading is visible until the blueprints arrive, then originals and copies read as one line each`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<OwnedBlueprint>>()

        val viewModel = viewModelFor(BlueprintSource { pending.await() }, noJobs)

        assertThat(viewModel.state.value.loading).isTrue()
        pending.complete(
            listOf(
                blueprint(1001, "Rifter Blueprint", BlueprintKind.Original, me = 10, te = 20),
                blueprint(1002, "Rifter Blueprint", BlueprintKind.Copy, me = 2, te = 4, runs = 4),
            ),
        )
        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.lines.map(BlueprintLine::text)).containsExactly(
            "Alice  Rifter Blueprint  BPO  ME 10  TE 20  @ Jita IV - Moon 4",
            "Alice  Rifter Blueprint  BPC 4 runs  ME 2  TE 4  @ Jita IV - Moon 4",
        )
    }

    @Test
    fun `a blueprint in use shows the activity and the time left`() {
        system.store(1, "Alice")
        val jobs = JobSource { listOf(job(blueprintId = 1001, activity = IndustryActivity.Copying, endsIn = 134)) }

        val viewModel = viewModelFor(BlueprintSource { listOf(blueprint(1001, "Rifter Blueprint")) }, jobs)

        val line = viewModel.state.value.lines.single()
        assertThat(line.text).endsWith("IN USE: C, ETL 2h 14m")
        assertThat(line.inUse).isTrue()
    }

    @Test
    fun `search narrows the list and free only hides blueprints in use`() {
        system.store(1, "Alice")
        val source = BlueprintSource {
            listOf(blueprint(1001, "Rifter Blueprint"), blueprint(1002, "Merlin Blueprint"))
        }
        val jobs = JobSource { listOf(job(blueprintId = 1002)) }
        val viewModel = viewModelFor(source, jobs)

        viewModel.setQuery("merl")
        assertThat(viewModel.state.value.lines.map(BlueprintLine::inUse)).containsExactly(true)

        viewModel.setFreeOnly(true)
        assertThat(viewModel.state.value.lines).isEmpty()

        viewModel.setQuery("")
        assertThat(viewModel.state.value.lines.map { it.text.substringAfter("  ").substringBefore("  ") })
            .containsExactly("Rifter Blueprint")
    }

    @Test
    fun `saved blueprints are visible while the network refresh is still pending`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<OwnedBlueprint>>()
        val source = object : BlueprintSource {
            override suspend fun load(character: eve.hq.core.character.EveCharacter) = pending.await()
            override fun cached(character: eve.hq.core.character.EveCharacter) = listOf(blueprint(1001, "Rifter Blueprint"))
        }

        val viewModel = viewModelFor(source, noJobs)

        assertThat(viewModel.state.value.loading).isTrue()
        assertThat(viewModel.state.value.lines.map { it.text.substringAfter("  ").substringBefore("  ") })
            .containsExactly("Rifter Blueprint")
        pending.complete(listOf(blueprint(1002, "Merlin Blueprint")))
        assertThat(viewModel.state.value.lines.map { it.text.substringAfter("  ").substringBefore("  ") })
            .containsExactly("Merlin Blueprint")
    }

    @Test
    fun `a character that fails to load is reported while the others are listed`() {
        system.store(1, "Alice")
        system.store(2, "Bob")

        val viewModel = viewModelFor(
            BlueprintSource { character ->
                if (character.name == "Alice") error("ESI is down") else listOf(blueprint(2, "Merlin Blueprint"))
            },
            noJobs,
        )

        assertThat(viewModel.state.value.lines.map { it.text.substringBefore("  ") }).containsExactly("Bob")
        assertThat(viewModel.state.value.failures.single().message).isEqualTo("ESI is down")
    }

    private val noJobs = JobSource { emptyList() }

    private fun viewModelFor(source: BlueprintSource, jobs: JobSource) = BlueprintsViewModel(
        ListCharacters(system.repository),
        ListBlueprints(source, jobs),
        clock,
        scope,
        dispatcher,
    )

    private fun blueprint(
        itemId: Long,
        name: String,
        kind: BlueprintKind = BlueprintKind.Original,
        me: Int = 0,
        te: Int = 0,
        runs: Int? = null,
    ) = OwnedBlueprint(
        itemId, AssetType(681, name), kind, me, te, runs, maxRuns = 300, AssetLocation(60003760, "Jita IV - Moon 4"),
    )

    private fun job(
        blueprintId: Long,
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        endsIn: Long = 60,
    ) = IndustryJob(
        jobId = 1,
        activity = activity,
        blueprintId = blueprintId,
        blueprint = JobItem(681, "Rifter Blueprint"),
        product = null,
        runs = 1,
        startedAt = now.minusSeconds(600),
        endsAt = now.plusSeconds(endsIn * 60),
        status = JobStatus.Active,
        facility = JobFacility(60003760, "Jita IV - Moon 4"),
    )
}
