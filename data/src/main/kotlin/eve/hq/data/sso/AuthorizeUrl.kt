package eve.hq.data.sso

import java.net.URI
import java.net.URLEncoder

object AuthorizeUrl {
    fun build(config: SsoConfig, challenge: String, state: String): URI {
        val query = listOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "scope" to config.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return URI.create("${config.authorizeUrl}?$query")
    }

    // URLEncoder is form encoding and writes a space as '+'; the SSO expects %20 in the query.
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
