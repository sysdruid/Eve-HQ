package eve.hq.data.sso

import java.util.Base64

fun fakeJwt(payload: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    fun part(text: String) = encoder.encodeToString(text.toByteArray())
    return "${part("""{"alg":"RS256","kid":"JWT-Signature-Key","typ":"JWT"}""")}.${part(payload)}.signature"
}
