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
                    startedAt = now.minusSeconds(600),
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
                startedAt = now.minusSeconds(600),
                endsAt = now.plus(Duration.ofHours(2)),
                status = JobStatus.Active,
                facilityName = "Jita IV - Moon 4",
                productId = 2,
                facilityId = 60003760,
                characterId = 1,
            ),
        )
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `a row keeps the type id of its product so it can be matched to a project, and none without a product`() = runBlocking<Unit> {
        val source = JobSource {
            listOf(aJob(productName = "Legion"), aJob(activity = IndustryActivity.MaterialEfficiency, productName = null))
        }

        val rows = ListJobs(source, clock)(listOf(alice)).rows

        assertThat(rows.map { it.productId }).containsExactlyInAnyOrder(2L, null)
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
        val row = aRow(startedAt = now.minusSeconds(10), endsAt = now.plusSeconds(90))

        assertThat(row.remaining(now)).isEqualTo(Duration.ofSeconds(90))
        assertThat(row.remaining(now.plusSeconds(500))).isEqualTo(Duration.ZERO)
        assertThat(row.isReady(now)).isFalse()
        assertThat(row.isReady(now.plusSeconds(90))).isTrue()
    }

    @Test
    fun `progress runs from zero at the start to one at the end and stays in that range`() {
        val row = aRow(startedAt = now, endsAt = now.plusSeconds(100))

        assertThat(row.progress(now.minusSeconds(50))).isEqualTo(0.0)
        assertThat(row.progress(now)).isEqualTo(0.0)
        assertThat(row.progress(now.plusSeconds(25))).isEqualTo(0.25)
        assertThat(row.progress(now.plusSeconds(100))).isEqualTo(1.0)
        assertThat(row.progress(now.plusSeconds(500))).isEqualTo(1.0)
    }

    @Test
    fun `a job ESI calls ready is complete and an instant job has no progress to wait for`() {
        val ready = aRow(startedAt = now, endsAt = now.plusSeconds(100), status = JobStatus.Ready)
        val instant = aRow(startedAt = now, endsAt = now)

        assertThat(ready.progress(now.plusSeconds(10))).isEqualTo(1.0)
        assertThat(instant.progress(now.minusSeconds(1))).isEqualTo(1.0)
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
    fun `cached jobs are listed without loading, skipping characters that have no saved jobs`() {
        val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
        val source = object : JobSource {
            override suspend fun load(character: EveCharacter): List<IndustryJob> = error("jobs must not be loaded")
            override fun cached(character: EveCharacter) =
                if (character.id == alice.id) listOf(aJob(productName = "Rifter"), aJob(status = JobStatus.Delivered)) else null
        }

        val result = ListJobs(source, clock).cached(listOf(alice, bob))

        assertThat(result.rows.map { it.characterName to it.itemName }).containsExactly("Alice" to "Rifter")
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `cancelling the load is not reported as a character failure`() {
        val source = JobSource { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { runBlocking { ListJobs(source, clock)(listOf(alice)) } }
    }

    private fun aRow(startedAt: Instant, endsAt: Instant, status: JobStatus = JobStatus.Active) = JobRow(
        characterName = "Alice",
        activity = IndustryActivity.Manufacturing,
        itemName = "Rifter",
        runs = 1,
        startedAt = startedAt,
        endsAt = endsAt,
        status = status,
        facilityName = "Jita",
        productId = null,
        facilityId = 60003760,
        characterId = 1,
    )

    private fun aJob(
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        blueprintName: String? = "Rifter Blueprint",
        productName: String? = "Rifter",
        runs: Int = 1,
        startedAt: Instant = now.minusSeconds(600),
        endsAt: Instant = now.plusSeconds(3600),
        status: JobStatus = JobStatus.Active,
        facilityName: String? = "Jita IV - Moon 4",
    ) = IndustryJob(
        jobId = 1,
        activity = activity,
        blueprintId = 1,
        blueprint = JobItem(1, blueprintName),
        product = productName?.let { JobItem(2, it) },
        runs = runs,
        startedAt = startedAt,
        endsAt = endsAt,
        status = status,
        facility = JobFacility(60003760, facilityName),
    )

    @Test
    fun `a row keeps the id of the facility the job runs in, to look up its structure`() = runBlocking<Unit> {
        val rows = ListJobs(JobSource { listOf(aJob()) }, clock)(listOf(alice)).rows

        assertThat(rows.single().facilityId).isEqualTo(60003760L)
    }
}
