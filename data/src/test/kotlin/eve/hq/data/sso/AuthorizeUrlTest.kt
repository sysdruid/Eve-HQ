package eve.hq.data.sso

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthorizeUrlTest {
    private val config = SsoConfig(
        clientId = "client-123",
        callbackPort = 8765,
        scopes = listOf("publicData", "esi-skills.read_skills.v1"),
    )

    private fun params(uri: URI): Map<String, String> = uri.rawQuery.split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    @Test
    fun `the URL carries client id, redirect, scopes, state and the S256 challenge`() {
        val uri = AuthorizeUrl.build(config, challenge = "the-challenge", state = "the-state")

        assertEquals("https://login.eveonline.com/v2/oauth/authorize", uri.toString().substringBefore("?"))
        assertEquals(
            mapOf(
                "response_type" to "code",
                "client_id" to "client-123",
                "redirect_uri" to "http://localhost:8765/callback",
                "scope" to "publicData esi-skills.read_skills.v1",
                "state" to "the-state",
                "code_challenge" to "the-challenge",
                "code_challenge_method" to "S256",
            ),
            params(uri),
        )
    }

    @Test
    fun `scopes are separated by an encoded space, not a plus sign`() {
        val uri = AuthorizeUrl.build(config, challenge = "c", state = "s")

        assertTrue("scope=publicData%20esi-skills.read_skills.v1" in uri.rawQuery, uri.rawQuery)
    }
}
