package eve.hq.data.sso

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginFailed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class EveSsoAuthenticatorTest {
    private val config = SsoConfig(clientId = "client-123", callbackPort = freePort())

    private val aliceToken = fakeJwt(
        """{"sub":"CHARACTER:EVE:2112625428","name":"Alice Example","exp":1788864000,"aud":["client-123","EVE Online"]}""",
    )
    private val tokenResponse =
        """{"access_token":"$aliceToken","expires_in":1199,"token_type":"Bearer","refresh_token":"refresh-1"}"""

    private val authorizeUrls = mutableListOf<URI>()

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun params(uri: URI) = uri.rawQuery.split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    private fun cooperativeBrowser(code: String = "the-code"): (URI) -> Unit = { authorizeUrl ->
        authorizeUrls += authorizeUrl
        thread {
            val state = params(authorizeUrl).getValue("state")
            JdkHttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${config.redirectUri}?code=$code&state=$state")).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    private fun tokens(status: HttpStatusCode = HttpStatusCode.OK, body: String = tokenResponse) = SsoTokenClient(
        HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }),
        config,
    )

    @Test
    fun `a completed browser login yields the character with its tokens`() = runBlocking {
        val authenticator = EveSsoAuthenticator(config, tokens(), cooperativeBrowser())

        val character = authenticator.login()

        assertEquals(
            EveCharacter(
                id = 2112625428,
                name = "Alice Example",
                token = AuthToken(aliceToken, "refresh-1", Instant.ofEpochSecond(1788864000)),
            ),
            character,
        )
    }

    @Test
    fun `the verifier sent to the token endpoint matches the challenge in the authorize URL`() = runBlocking {
        var sentVerifier: String? = null
        val engine = MockEngine { request ->
            sentVerifier = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                .parseUrlEncodedParameters()["code_verifier"]
            respond(tokenResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

        EveSsoAuthenticator(config, SsoTokenClient(HttpClient(engine), config), cooperativeBrowser()).login()

        assertEquals(Pkce.challengeFor(sentVerifier!!), params(authorizeUrls.single()).getValue("code_challenge"))
    }

    @Test
    fun `a browser that never calls back fails the login after the timeout`() {
        val authenticator = EveSsoAuthenticator(config, tokens(), openBrowser = {}, timeout = 100.milliseconds)

        assertFailsWith<LoginFailed> { runBlocking { authenticator.login() } }
    }

    @Test
    fun `the callback port is free again after a login, so logging in twice works`() = runBlocking {
        val authenticator = EveSsoAuthenticator(config, tokens(), cooperativeBrowser())

        authenticator.login()
        authenticator.login()

        assertEquals(2, authorizeUrls.size)
    }

    @Test
    fun `a rejected code exchange fails the login`() {
        val rejected = tokens(HttpStatusCode.BadRequest, """{"error":"invalid_grant","error_description":"code already used"}""")

        assertFailsWith<LoginFailed> { runBlocking { EveSsoAuthenticator(config, rejected, cooperativeBrowser()).login() } }
    }

    @Test
    fun `an SSO outage fails the login`() {
        val down = tokens(HttpStatusCode.ServiceUnavailable, "maintenance")

        assertFailsWith<LoginFailed> { runBlocking { EveSsoAuthenticator(config, down, cooperativeBrowser()).login() } }
    }

    @Test
    fun `an unreachable SSO fails the login and keeps the cause`() {
        val offline = SsoTokenClient(HttpClient(MockEngine { throw IOException("no route to host") }), config)

        val failure = assertFailsWith<LoginFailed> {
            runBlocking { EveSsoAuthenticator(config, offline, cooperativeBrowser()).login() }
        }

        assertEquals("no route to host", failure.cause?.message)
    }

    @Test
    fun `a token that is not a character token fails the login`() {
        val odd = tokens(body = """{"access_token":"garbage","expires_in":1199,"refresh_token":"r"}""")

        assertFailsWith<LoginFailed> { runBlocking { EveSsoAuthenticator(config, odd, cooperativeBrowser()).login() } }
    }
}
