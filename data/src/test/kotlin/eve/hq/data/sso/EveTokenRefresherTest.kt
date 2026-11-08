package eve.hq.data.sso

import eve.hq.core.character.AuthToken
import eve.hq.core.character.TokenRevoked
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EveTokenRefresherTest {
    private fun refresher(status: HttpStatusCode = HttpStatusCode.OK, body: String): EveTokenRefresher {
        val engine = MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return EveTokenRefresher(SsoTokenClient(HttpClient(engine), SsoConfig(clientId = "client-123")))
    }

    private val refreshedAccess = fakeJwt("""{"sub":"CHARACTER:EVE:1","name":"Alice","exp":1788864000}""")

    @Test
    fun `a refresh returns the new tokens with the rotated refresh token and the expiry from the access token`() = runBlocking {
        val token = refresher(
            body = """{"access_token":"$refreshedAccess","expires_in":1199,"token_type":"Bearer","refresh_token":"rotated"}""",
        ).refresh("old")

        assertEquals(AuthToken(refreshedAccess, "rotated", Instant.ofEpochSecond(1788864000)), token)
    }

    @Test
    fun `an invalid grant is reported as a revoked token with the SSO's reason`() {
        val revoked = refresher(
            HttpStatusCode.BadRequest,
            """{"error":"invalid_grant","error_description":"The refresh token is expired."}""",
        )

        val failure = assertFailsWith<TokenRevoked> { runBlocking { revoked.refresh("old") } }

        assertEquals("The refresh token is expired.", failure.message)
    }

    @Test
    fun `a network failure propagates unchanged`() {
        val offline = EveTokenRefresher(
            SsoTokenClient(HttpClient(MockEngine { throw IOException("no route to host") }), SsoConfig(clientId = "c")),
        )

        assertFailsWith<IOException> { runBlocking { offline.refresh("old") } }
    }
}
