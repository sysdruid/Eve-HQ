package eve.hq.data.sso

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        base64Url(ByteArray(32).also(random::nextBytes))

    fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
