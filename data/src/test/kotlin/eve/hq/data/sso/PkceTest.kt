package eve.hq.data.sso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun `the challenge is the S256 of the verifier (RFC 7636 appendix B vector)`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
    }

    @Test
    fun `a new verifier is 32 random bytes as 43 URL-safe characters, different each time`() {
        val first = Pkce.newVerifier()
        val second = Pkce.newVerifier()

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")), first)
        assertNotEquals(first, second)
    }
}
