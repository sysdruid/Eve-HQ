package eve.hq.core.industry

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ListJobsTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))

    @Test
    fun `given no characters, the job list is empty and the source is not called`() = runBlocking<Unit> {
        val source = JobSource { error("job source must not be called") }

        val result = ListJobs(source, clock)(emptyList())

        assertThat(result).isEqualTo(JobListing(emptyList(), emptyList()))
    }

    @Test
    fun `a job row shows who runs what, how many runs, when it ends and where`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(
                aJob(
                    activity = IndustryActivity.Manufacturing,
                    blueprintName = "Rifter Blueprint",
                    productName = "Rifter",
                    runs = 10,
                    endsAt = now.plus(Duration.ofHours(2)),
                    facilityName = "Jita IV - Moon 4",
                ),
            )
        }

        val result = ListJobs(source, clock)(listOf(alice))

        assertThat(result.rows).containsExactly(
            JobRow(
                characterName = "Alice",
                activity = IndustryActivity.Manufacturing,
                itemName = "Rifter",
                runs = 10,
                endsAt = now.plus(Duration.ofHours(2)),
                status = JobStatus.Active,
                facilityName = "Jita IV - Moon 4",
            ),
        )
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `a job without a product shows its blueprint name`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(aJob(activity = IndustryActivity.MaterialEfficiency, productName = null))
        }

        val result = ListJobs(source, clock)(listOf(alice))

        assertThat(result.rows.single().itemName).isEqualTo("Rifter Blueprint")
    }

    @Test
    fun `unresolved names show the type id and location id instead of failing`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(aJob(blueprintName = null, productName = null, facilityName = null))
        }

        val result = ListJobs(source, clock)(listOf(alice))

        assertThat(result.rows.single().itemName).isEqualTo("Unknown type (1)")
        assertThat(result.rows.single().facilityName).isEqualTo("Location 60003760")
    }

    @Test
    fun `delivered, cancelled and reverted jobs are not listed`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(
                aJob(status = JobStatus.Delivered),
                aJob(status = JobStatus.Cancelled),
                aJob(status = JobStatus.Reverted),
                aJob(status = JobStatus.Paused),
            )
        }

        val result = ListJobs(source, clock)(listOf(alice))

        assertThat(result.rows.map(JobRow::status)).containsExactly(JobStatus.Paused)
    }

    @Test
    fun `finished jobs come first, then the rest by end time`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(
                aJob(productName = "Later", endsAt = now.plusSeconds(7200)),
                aJob(productName = "Sooner", endsAt = now.plusSeconds(60)),
                aJob(productName = "Finished by clock", endsAt = now.minusSeconds(60)),
                aJob(productName = "Finished by ESI", status = JobStatus.Ready, endsAt = now.minusSeconds(3600)),
            )
        }

        val result = ListJobs(source, clock)(listOf(alice))

        assertThat(result.rows.map(JobRow::itemName))
            .containsExactly("Finished by ESI", "Finished by clock", "Sooner", "Later")
    }

    @Test
    fun `remaining time counts down to the end and stops at zero`() {
        val row = JobRow("Alice", IndustryActivity.Copying, "Rifter Blueprint", 1, now.plusSeconds(90), JobStatus.Active, "Jita")

        assertThat(row.remaining(now)).isEqualTo(Duration.ofSeconds(90))
        assertThat(row.remaining(now.plusSeconds(500))).isEqualTo(Duration.ZERO)
        assertThat(row.isReady(now)).isFalse()
        assertThat(row.isReady(now.plusSeconds(90))).isTrue()
    }

    @Test
    fun `a character whose jobs cannot be loaded is reported and the others are still listed`() = runBlocking<Unit> {
        val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
        val source = JobSource { character ->
            if (character.id == alice.id) error("ESI is down") else listOf(aJob(productName = "Merlin"))
        }

        val result = ListJobs(source, clock)(listOf(alice, bob))

        assertThat(result.rows.map(JobRow::characterName)).containsExactly("Bob")
        assertThat(result.failures).containsExactly(JobLoadFailure(1, "Alice", "ESI is down"))
    }

    @Test
    fun `cancelling the load is not reported as a character failure`() {
        val source = JobSource { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { runBlocking { ListJobs(source, clock)(listOf(alice)) } }
    }

    private fun aJob(
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        blueprintName: String? = "Rifter Blueprint",
        productName: String? = "Rifter",
        runs: Int = 1,
        endsAt: Instant = now.plusSeconds(3600),
        status: JobStatus = JobStatus.Active,
        facilityName: String? = "Jita IV - Moon 4",
    ) = IndustryJob(
        jobId = 1,
        activity = activity,
        blueprint = JobItem(1, blueprintName),
        product = productName?.let { JobItem(2, it) },
        runs = runs,
        endsAt = endsAt,
        status = status,
        facility = JobFacility(60003760, facilityName),
    )
}
