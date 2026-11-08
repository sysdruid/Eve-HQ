package eve.hq.data.esi

import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.CachedJobPage
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobPageCache
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import eve.hq.data.LocationNameCache
import eve.hq.data.StaticTypeNameRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant

class EveJobSource(
    private val http: HttpClient,
    baseUrl: String = "https://esi.evetech.net/latest/",
    staticTypeNames: StaticTypeNameRepository? = null,
    private val pageCache: JobPageCache? = null,
    private val clock: Clock = Clock.systemUTC(),
    locationNames: LocationNameCache? = null,
) : JobSource {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }
    private val names = EsiNames(http, baseUrl, staticTypeNames, locationNames, clock)

    override suspend fun load(character: EveCharacter): List<IndustryJob> {
        val jobs = json.decodeFromString<List<EsiJob>>(loadPage(character))
        val typeNames = names.typeNames(jobs.flatMap { listOfNotNull(it.blueprintTypeId, it.productTypeId) })
        val facilityNames = jobs.map(EsiJob::facilityId).distinct().associateWith { id ->
            names.stationOrStructureName(id, character.token.accessToken)
        }
        return jobs.map { it.toJob(typeNames, facilityNames) }
    }

    override fun cached(character: EveCharacter): List<IndustryJob>? {
        val page = pageCache?.find(character.id) ?: return null
        val jobs = json.decodeFromString<List<EsiJob>>(page.body)
        val typeNames = names.cachedTypeNames(jobs.flatMap { listOfNotNull(it.blueprintTypeId, it.productTypeId) })
        val facilityNames = jobs.map(EsiJob::facilityId).distinct().associateWith(names::cachedLocationName)
        return jobs.map { it.toJob(typeNames, facilityNames) }
    }

    private fun EsiJob.toJob(typeNames: Map<Long, String>, facilityNames: Map<Long, String?>) = IndustryJob(
        jobId = jobId,
        activity = activity(activityId),
        blueprintId = blueprintId,
        blueprint = JobItem(blueprintTypeId, typeNames[blueprintTypeId]),
        product = productTypeId?.let { JobItem(it, typeNames[it]) },
        runs = runs,
        startedAt = Instant.parse(startDate),
        endsAt = Instant.parse(endDate),
        status = status(status),
        facility = JobFacility(facilityId, facilityNames[facilityId]),
    )

    private suspend fun loadPage(character: EveCharacter): String {
        val cached = pageCache?.find(character.id)
        if (cached?.expiresAt?.isAfter(clock.instant()) == true) return cached.body
        val response = http.get("${baseUrl}characters/${character.id}/industry/jobs/") {
            bearerAuth(character.token.accessToken)
            cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        val page = if (response.status == HttpStatusCode.NotModified && cached != null) {
            cached.copy(etag = response.headers[HttpHeaders.ETag] ?: cached.etag, expiresAt = response.expiresAt())
        } else {
            CachedJobPage(response.bodyOrFail("industry jobs", "industry job"), response.headers[HttpHeaders.ETag], response.expiresAt())
        }
        pageCache?.save(character.id, page)
        return page.body
    }

    private fun activity(id: Int) = when (id) {
        1 -> IndustryActivity.Manufacturing
        3 -> IndustryActivity.TimeEfficiency
        4 -> IndustryActivity.MaterialEfficiency
        5 -> IndustryActivity.Copying
        7 -> IndustryActivity.ReverseEngineering
        8 -> IndustryActivity.Invention
        // Both ids are accepted for reactions: the ESI documentation and the SDE disagree on which one is used.
        9, 11 -> IndustryActivity.Reaction
        else -> throw IllegalStateException("Unknown ESI industry activity $id")
    }

    private fun status(value: String) = when (value) {
        "active" -> JobStatus.Active
        "paused" -> JobStatus.Paused
        "ready" -> JobStatus.Ready
        "delivered" -> JobStatus.Delivered
        "cancelled" -> JobStatus.Cancelled
        "reverted" -> JobStatus.Reverted
        else -> throw IllegalStateException("Unknown ESI job status $value")
    }

    @Serializable
    private data class EsiJob(
        @SerialName("job_id") val jobId: Long,
        @SerialName("activity_id") val activityId: Int,
        @SerialName("blueprint_id") val blueprintId: Long,
        @SerialName("blueprint_type_id") val blueprintTypeId: Long,
        @SerialName("product_type_id") val productTypeId: Long? = null,
        val runs: Int,
        @SerialName("start_date") val startDate: String,
        @SerialName("end_date") val endDate: String,
        val status: String,
        @SerialName("facility_id") val facilityId: Long,
    )
}
