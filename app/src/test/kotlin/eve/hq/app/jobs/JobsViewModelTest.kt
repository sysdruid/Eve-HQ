package eve.hq.app.jobs

import eve.hq.app.characters.AppSystem
import eve.hq.core.character.ListCharacters
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import eve.hq.core.industry.ListJobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)
    private val clock = SteppingClock(Instant.parse("2026-11-08T12:00:00Z"))

    @Test
    fun `loading is visible until the jobs arrive, then each job reads as one line`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<IndustryJob>>()

        val viewModel = viewModelFor(JobSource { pending.await() })

        assertThat(viewModel.state.value.loading).isTrue()
        pending.complete(listOf(manufacturing("Rifter", runs = 10, endsIn = Duration.ofMinutes(134), status = JobStatus.Active)))
        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.lines.map(JobLine::text))
            .containsExactly("Alice-Rifter [10] M  ETL: 2h 14m  Active @ Jita IV - Moon 4")
    }

    @Test
    fun `a finished job shows done and is flagged ready`() {
        system.store(1, "Alice")

        val viewModel = viewModelFor(JobSource { listOf(manufacturing("Rifter", endsIn = Duration.ofMinutes(-5))) })

        val line = viewModel.state.value.lines.single()
        assertThat(line.text).isEqualTo("Alice-Rifter [1] M  ETL: done  Ready @ Jita IV - Moon 4")
        assertThat(line.status).isEqualTo(JobLineStatus.Ready)
        assertThat(line.progress).isEqualTo(1f)
    }

    @Test
    fun `every activity has its short code and unnamed places fall back to ids`() {
        system.store(1, "Alice")
        val activities = IndustryActivity.entries
        val jobs = activities.mapIndexed { index, activity ->
            manufacturing("Item $index", activity = activity, facility = JobFacility(77, null))
        }

        val viewModel = viewModelFor(JobSource { jobs })

        assertThat(viewModel.state.value.lines.map { it.text.substringAfter("[1] ").substringBefore("  ") })
            .containsExactly("M", "TE", "ME", "C", "I", "RE", "R")
        assertThat(viewModel.state.value.lines.first().text).endsWith("@ Location 77")
    }

    @Test
    fun `the countdown moves on its own as time passes, without asking ESI again`() {
        system.store(1, "Alice")
        var loads = 0
        val viewModel = viewModelFor(JobSource {
            loads++
            listOf(manufacturing("Rifter", endsIn = Duration.ofMinutes(90)))
        })

        clock.advance(Duration.ofMinutes(31))
        dispatcher.scheduler.advanceTimeBy(Duration.ofMinutes(31).toMillis())

        assertThat(viewModel.state.value.lines.single().text).contains("ETL: 59m")
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `a running job's bar fills as time passes and updates every few seconds`() {
        system.store(1, "Alice")
        val viewModel = viewModelFor(JobSource {
            listOf(manufacturing("Rifter", startedAgo = Duration.ZERO, endsIn = Duration.ofSeconds(100)))
        })
        val before = viewModel.state.value.lines.single()

        clock.advance(Duration.ofSeconds(5))
        dispatcher.scheduler.advanceTimeBy(5_000)
        dispatcher.scheduler.runCurrent() // advanceTimeBy stops just before the tick due at exactly 5s

        assertThat(before.status).isEqualTo(JobLineStatus.Running)
        assertThat(before.progress).isEqualTo(0f)
        assertThat(viewModel.state.value.lines.single().progress).isEqualTo(0.05f)
    }

    @Test
    fun `a paused job is marked as paused`() {
        system.store(1, "Alice")

        val viewModel = viewModelFor(JobSource { listOf(manufacturing("Rifter", status = JobStatus.Paused)) })

        assertThat(viewModel.state.value.lines.single().status).isEqualTo(JobLineStatus.Paused)
    }

    @Test
    fun `a character that fails to load is reported while the others are listed`() {
        system.store(1, "Alice")
        system.store(2, "Bob")

        val viewModel = viewModelFor(JobSource { character ->
            if (character.name == "Alice") error("ESI is down") else listOf(manufacturing("Merlin"))
        })

        assertThat(viewModel.state.value.lines.map { it.text.substringBefore("-") }).containsExactly("Bob")
        assertThat(viewModel.state.value.failures.single().message).isEqualTo("ESI is down")
    }

    private fun viewModelFor(source: JobSource) =
        JobsViewModel(ListCharacters(system.repository), ListJobs(source, clock), clock, scope, dispatcher)

    private fun manufacturing(
        product: String,
        runs: Int = 1,
        startedAgo: Duration = Duration.ofMinutes(10),
        endsIn: Duration = Duration.ofHours(1),
        status: JobStatus = JobStatus.Active,
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        facility: JobFacility = JobFacility(60003760, "Jita IV - Moon 4"),
    ) = IndustryJob(
        jobId = 1,
        activity = activity,
        blueprintId = 1,
        blueprint = JobItem(1, "$product Blueprint"),
        product = JobItem(2, product),
        runs = runs,
        startedAt = clock.instant().minus(startedAgo),
        endsAt = clock.instant().plus(endsIn),
        status = status,
        facility = facility,
    )

    private class SteppingClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now += by
        }

        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
