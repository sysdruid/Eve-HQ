package eve.hq.data.sso

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccessTokenTest {
    @Test
    fun `character id, name and expiry come from the claims, ignoring everything else`() {
        val token = fakeJwt(
            """{"scp":["publicData"],"jti":"abc","kid":"JWT-Signature-Key","sub":"CHARACTER:EVE:2112625428",
               "azp":"client-123","tenant":"tranquility","tier":"live","region":"world",
               "aud":["client-123","EVE Online"],"name":"Alice Example","owner":"hash=",
               "exp":1788864000,"iat":1788862800,"iss":"https://login.eveonline.com"}""",
        )

        assertEquals(
            TokenClaims(
                characterId = 2112625428,
                characterName = "Alice Example",
                expiresAt = Instant.ofEpochSecond(1788864000),
            ),
            AccessToken.claims(token),
        )
    }

    @Test
    fun `something that is not a JWT is rejected`() {
        assertFailsWith<MalformedToken> { AccessToken.claims("not-a-token") }
    }

    @Test
    fun `a token whose subject is not a character is rejected`() {
        val token = fakeJwt("""{"sub":"CORPORATION:EVE:98000001","name":"Some Corp","exp":1788864000}""")

        assertFailsWith<MalformedToken> { AccessToken.claims(token) }
    }
}
