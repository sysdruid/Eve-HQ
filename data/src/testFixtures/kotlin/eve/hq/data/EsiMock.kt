package eve.hq.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import org.assertj.core.api.Assertions.assertThat

class EsiMock(configure: EsiMock.() -> Unit) {
    private val responses = mutableMapOf<Route, (EsiRequest) -> EsiResponse>()
    private val capturedRequests = mutableListOf<EsiRequest>()

    val baseUrl = "https://esi.test/latest/"
    val requests: List<EsiRequest>
        get() = capturedRequests.toList()

    fun assertNoRequest(predicate: (EsiRequest) -> Boolean) {
        assertRequests(predicate, "No ESI request should match the predicate", expected = false)
    }

    fun assertAnyRequest(predicate: (EsiRequest) -> Boolean) {
        assertRequests(predicate, "Expected an ESI request matching the predicate", expected = true)
    }

    private fun assertRequests(
        predicate: (EsiRequest) -> Boolean,
        expectation: String,
        expected: Boolean,
    ) {
        val matchingRequests = capturedRequests.filter(predicate).map(::requestSummary)
        val capturedSummaries = capturedRequests.map(::requestSummary)
        val description = "$expectation; captured ESI requests (method, path, page only): $capturedSummaries"
        if (expected) {
            assertThat(matchingRequests).describedAs(description).isNotEmpty()
        } else {
            assertThat(matchingRequests).describedAs(description).isEmpty()
        }
    }

    private fun requestSummary(request: EsiRequest): String = buildString {
        append(request.method.value).append(' ').append(request.path)
        request.queryParameters["page"]?.toIntOrNull()?.let { append(" page=").append(it) }
    }

    init {
        configure()
    }

    val client = HttpClient(MockEngine { request ->
        val esiRequest = EsiRequest(
            method = request.method,
            path = request.url.encodedPath,
            queryParameters = request.url.parameters,
            headers = request.headers,
            body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty(),
        )
        capturedRequests += esiRequest
        val route = Route(esiRequest.method, esiRequest.path, esiRequest.queryParameters["page"]?.toIntOrNull())
        val handler = responses[route]
            ?: responses[route.copy(page = null)]
            ?: error("Unexpected ESI request: ${esiRequest.method.value} ${request.url}")
        val response = handler(esiRequest)
        respond(response.body, response.status, response.toHeaders())
    })

    fun characterAssets(characterId: Long) = get("/characters/$characterId/assets/")

    fun characterAssetNames(characterId: Long) = post("/characters/$characterId/assets/names/")

    fun universeNames() = post("/universe/names/")

    fun universeStations(stationId: Long) = get("/universe/stations/$stationId/")

    fun universeStructures(structureId: Long) = get("/universe/structures/$structureId/")

    private fun get(path: String) = Endpoint(HttpMethod.Get, "/latest$path")

    private fun post(path: String) = Endpoint(HttpMethod.Post, "/latest$path")

    private fun register(route: Route, handler: (EsiRequest) -> EsiResponse) {
        check(responses.putIfAbsent(route, handler) == null) { "Duplicate ESI mock route: $route" }
    }

    inner class Endpoint internal constructor(
        private val method: HttpMethod,
        private val path: String,
        private val page: Int? = null,
    ) {
        fun page(number: Int) = Endpoint(method, path, number)

        fun assertRequested() {
            assertRequests(::matches, "Expected ESI endpoint ${endpointSummary()}", expected = true)
        }

        fun assertNotRequested() {
            assertRequests(::matches, "ESI endpoint ${endpointSummary()} should not have been requested", expected = false)
        }

        fun assertPagesRequested(vararg expectedPages: Int) {
            val pageRequests = capturedRequests.filter { it.method == method && it.path == path }
            val actualPages = pageRequests.map(::pageSummary)
            val expectedSequence = expectedPages.map(Int::toString)
            val description = "Expected ESI endpoint ${endpointSummary()} page sequence $expectedSequence; " +
                "captured page sequence: $actualPages"

            assertThat(actualPages).describedAs(description).containsExactlyElementsOf(expectedSequence)
        }

        private fun matches(request: EsiRequest): Boolean =
            request.method == method && request.path == path &&
                (page == null || request.queryParameters["page"]?.toIntOrNull() == page)

        private fun pageSummary(request: EsiRequest): String = when {
            "page" !in request.queryParameters -> "<missing>"
            else -> request.queryParameters["page"]?.toIntOrNull()?.toString() ?: "<invalid>"
        }

        private fun endpointSummary(): String = buildString {
            append(method.value).append(' ').append(path)
            page?.let { append(" page=").append(it) }
        }

        fun willReturn(
            body: String,
            status: HttpStatusCode = HttpStatusCode.OK,
            headers: Map<String, String> = emptyMap(),
        ) {
            register(Route(method, path, page)) { EsiResponse(body, status, headers) }
        }

        fun willRespond(handler: (EsiRequest) -> EsiResponse) {
            register(Route(method, path, page), handler)
        }

        fun willFail(exception: Exception) {
            register(Route(method, path, page)) { throw exception }
        }
    }

    data class EsiRequest(
        val method: HttpMethod,
        val path: String,
        val queryParameters: Parameters,
        val headers: Headers,
        val body: String,
    )

    data class EsiResponse(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val headers: Map<String, String> = emptyMap(),
    ) {
        internal fun toHeaders(): Headers = headersOf(
            *(mapOf(HttpHeaders.ContentType to ContentType.Application.Json.toString()) + headers)
                .map { (name, value) -> name to listOf(value) }
                .toTypedArray(),
        )
    }

    private data class Route(val method: HttpMethod, val path: String, val page: Int?)
}