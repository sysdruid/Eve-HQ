package eve.hq.core.industry

import eve.hq.core.character.EveCharacter
import eve.hq.core.attempt
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class IndustryActivity { Manufacturing, TimeEfficiency, MaterialEfficiency, Copying, Invention, ReverseEngineering, Reaction }

/** ESI's own job status; whether an active job is actually finished is derived from the clock. */
enum class JobStatus(val ongoing: Boolean) {
    Active(true), Paused(true), Ready(true), Delivered(false), Cancelled(false), Reverted(false),
}

data class JobItem(val id: Long, val name: String?)

data class JobFacility(val id: Long, val name: String?)

data class IndustryJob(
    val jobId: Long,
    val activity: IndustryActivity,
    /** Item id of the blueprint being used, to match it against the character's blueprint list. */
    val blueprintId: Long,
    val blueprint: JobItem,
    val product: JobItem?,
    val runs: Int,
    val startedAt: Instant,
    val endsAt: Instant,
    val status: JobStatus,
    val facility: JobFacility,
)

fun interface JobSource {
    suspend fun load(character: EveCharacter): List<IndustryJob>
}

data class JobRow(
    val characterName: String,
    val activity: IndustryActivity,
    val itemName: String,
    val runs: Int,
    val startedAt: Instant,
    val endsAt: Instant,
    val status: JobStatus,
    val facilityName: String,
) {
    /** Fraction of the job's duration that has passed, from 0.0 to 1.0. */
    fun progress(now: Instant): Double {
        if (isReady(now)) return 1.0
        val total = Duration.between(startedAt, endsAt)
        if (total.isZero || total.isNegative) return 1.0
        return (Duration.between(startedAt, now).toMillis().toDouble() / total.toMillis()).coerceIn(0.0, 1.0)
    }

    fun remaining(now: Instant): Duration = Duration.between(now, endsAt).coerceAtLeast(Duration.ZERO)

    // ESI keeps a finished job "active" until delivery, so the clock decides.
    fun isReady(now: Instant) = status == JobStatus.Ready || (status == JobStatus.Active && !endsAt.isAfter(now))
}

data class JobLoadFailure(val characterId: Long, val characterName: String, val message: String)

data class JobListing(val rows: List<JobRow>, val failures: List<JobLoadFailure>)

class ListJobs(private val source: JobSource, private val clock: Clock) {
    suspend operator fun invoke(characters: List<EveCharacter>): JobListing {
        val now = clock.instant()
        val results = characters.map { character -> character to attempt { source.load(character) } }
        val rows = results.flatMap { (character, result) ->
            result.getOrDefault(emptyList()).filter { it.status.ongoing }.map { it.toRow(character) }
        }.sortedWith(compareBy<JobRow> { !it.isReady(now) }.thenBy { it.endsAt })
        val failures = results.mapNotNull { (character, result) ->
            result.exceptionOrNull()?.let { JobLoadFailure(character.id, character.name, it.message ?: "Unable to load jobs") }
        }
        return JobListing(rows, failures)
    }

    private fun IndustryJob.toRow(character: EveCharacter) = JobRow(
        characterName = character.name,
        activity = activity,
        itemName = (product ?: blueprint).let { it.name ?: "Unknown type (${it.id})" },
        runs = runs,
        startedAt = startedAt,
        endsAt = endsAt,
        status = status,
        facilityName = facility.name ?: "Location ${facility.id}",
    )
}

data class CachedJobPage(val body: String, val etag: String?, val expiresAt: Instant?)

interface JobPageCache {
    fun find(characterId: Long): CachedJobPage?

    fun save(characterId: Long, page: CachedJobPage)
}
