package eve.hq.data.esi

import eve.hq.data.StaticTypeNameRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** Resolves ids to display names: types from static data first, then ESI; locations from ESI. */
internal class EsiNames(
    private val http: HttpClient,
    baseUrl: String,
    private val staticTypeNames: StaticTypeNameRepository?,
) {
    private val baseUrl = "${baseUrl.trimEnd('/')}/"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun typeNames(typeIds: Collection<Long>): Map<Long, String> {
        val ids = typeIds.distinct()
        val staticNames = staticTypeNames?.findNames(ids).orEmpty()
        return loadTypeNames(ids.filterNot(staticNames::containsKey)) + staticNames
    }

    /** For ids that may be either, such as a job facility or blueprint location: structure ids are far above station ids. */
    suspend fun stationOrStructureName(id: Long, accessToken: String): String? =
        if (id >= STRUCTURE_ID_MIN) structureName(id, accessToken) else stationName(id)

    suspend fun stationName(id: Long): String? = getLocation("universe/stations/$id/")

    suspend fun systemName(id: Long): String? = getLocation("universe/systems/$id/")

    suspend fun structureName(id: Long, accessToken: String): String? =
        getLocation("universe/structures/$id/", accessToken)

    private suspend fun loadTypeNames(typeIds: List<Long>): Map<Long, String> = typeIds.chunked(LOOKUP_BATCH_SIZE)
        .flatMap { ids -> loadTypeNamesBatch(ids) }
        .associate { it.id to it.name }

    private suspend fun loadTypeNamesBatch(ids: List<Long>): List<EsiName> {
        val pending = ArrayDeque<List<Long>>()
        val names = mutableListOf<EsiName>()
        pending.addLast(ids)
        var requests = 0
        while (pending.isNotEmpty() && requests < MAX_TYPE_LOOKUPS_PER_BATCH) {
            val batch = pending.removeFirst()
            requests++
            try {
                val response = http.post(url("universe/names/")) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(batch))
                }
                val body = response.bodyAsText()
                when {
                    response.status == HttpStatusCode.NotFound && batch.size > 1 -> {
                        val middle = batch.size / 2
                        // ESI rejects a whole batch for one unknown ID; bound retries if the endpoint itself is unavailable.
                        pending.addLast(batch.take(middle))
                        pending.addLast(batch.drop(middle))
                    }

                    response.status.isSuccess() -> names += json.decodeFromString<List<EsiName>>(body)
                }
            } catch (_: IOException) {
                break
            }
        }
        return names
    }

    private suspend fun getLocation(path: String, accessToken: String? = null): String? = try {
        http.get(url(path)) {
            if (accessToken != null) bearerAuth(accessToken)
        }.locationName()
    } catch (_: IOException) {
        null
    }

    private suspend fun HttpResponse.locationName(): String? {
        val body = bodyAsText()
        return if (status.isSuccess()) json.decodeFromString<EsiLocation>(body).name else null
    }

    private fun url(path: String) = baseUrl + path

    @Serializable
    private data class EsiName(val id: Long, val name: String)

    @Serializable
    private data class EsiLocation(val name: String)

    private companion object {
        const val LOOKUP_BATCH_SIZE = 1000
        const val MAX_TYPE_LOOKUPS_PER_BATCH = 8
        const val STRUCTURE_ID_MIN = 1_000_000_000_000L
    }
}
