package eve.hq.data.sso

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

/** The SSO rejected the grant: revoked, expired, or issued to another app. Expected per-character state. */
class InvalidGrant(message: String) : Exception(message)

/** Any other non-success answer from the SSO. */
class SsoError(val status: Int, val body: String) : Exception("SSO answered $status: $body")

class SsoTokenClient(private val http: HttpClient, private val config: SsoConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Wire(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    suspend fun exchange(code: String, verifier: String): TokenResponse = post(
        "grant_type" to "authorization_code",
        "code" to code,
        "client_id" to config.clientId,
        "code_verifier" to verifier,
    )

    suspend fun refresh(refreshToken: String): TokenResponse = post(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to config.clientId,
    )

    @Serializable
    private data class ErrorWire(val error: String? = null, @SerialName("error_description") val description: String? = null)

    private suspend fun post(vararg form: Pair<String, String>): TokenResponse {
        val response = http.submitForm(config.tokenUrl, formParameters = parameters { form.forEach { (k, v) -> append(k, v) } })
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val error = runCatching { json.decodeFromString<ErrorWire>(body) }.getOrNull()
            if (response.status == HttpStatusCode.BadRequest && error?.error == "invalid_grant") {
                throw InvalidGrant(error.description ?: "invalid_grant")
            }
            throw SsoError(response.status.value, body)
        }
        val wire = json.decodeFromString<Wire>(body)
        return TokenResponse(wire.accessToken, wire.refreshToken, wire.expiresIn)
    }
}
