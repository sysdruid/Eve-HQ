package eve.hq.data.sso

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SsoTokenClientTest {
    private val config = SsoConfig(clientId = "client-123")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String): SsoTokenClient {
        val engine = MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return SsoTokenClient(HttpClient(engine), config)
    }

    private fun formOf(request: HttpRequestData): Parameters =
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()

    private val tokenJson = """{"access_token":"access-1","expires_in":1199,"token_type":"Bearer","refresh_token":"refresh-1","scope":"publicData"}"""

    @Test
    fun `exchanging a code posts the PKCE form and parses the token, ignoring unknown fields`() = runBlocking {
        val response = client(body = tokenJson).exchange(code = "the-code", verifier = "the-verifier")

        assertEquals(TokenResponse("access-1", "refresh-1", 1199), response)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://login.eveonline.com/v2/oauth/token", request.url.toString())
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to "the-code",
                "client_id" to "client-123",
                "code_verifier" to "the-verifier",
            ),
            formOf(request).entries().associate { it.key to it.value.single() },
        )
    }

    @Test
    fun `refreshing posts the refresh form and returns the rotated refresh token`() = runBlocking {
        val rotated = tokenJson.replace("refresh-1", "refresh-2")

        val response = client(body = rotated).refresh("refresh-1")

        assertEquals("refresh-2", response.refreshToken)
        assertEquals(
            mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1", "client_id" to "client-123"),
            formOf(requests.single()).entries().associate { it.key to it.value.single() },
        )
    }

    @Test
    fun `a 400 invalid_grant is reported as an invalid grant`() {
        val invalid = client(
            HttpStatusCode.BadRequest,
            """{"error":"invalid_grant","error_description":"The refresh token is expired."}""",
        )

        val failure = assertFailsWith<InvalidGrant> { runBlocking { invalid.refresh("refresh-1") } }

        assertEquals("The refresh token is expired.", failure.message)
    }

    @Test
    fun `any other error answer is reported with its status and body`() {
        val broken = client(HttpStatusCode.InternalServerError, "oops")

        val failure = assertFailsWith<SsoError> { runBlocking { broken.refresh("refresh-1") } }

        assertEquals(500, failure.status)
        assertEquals("oops", failure.body)
    }

    @Test
    fun `a network failure propagates unchanged`() {
        val engine = MockEngine { throw IOException("connection reset") }
        val offline = SsoTokenClient(HttpClient(engine), config)

        assertFailsWith<IOException> { runBlocking { offline.refresh("refresh-1") } }
    }
}
