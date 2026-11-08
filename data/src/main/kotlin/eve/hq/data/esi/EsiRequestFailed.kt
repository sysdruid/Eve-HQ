package eve.hq.data.esi

import eve.hq.core.character.LoginRequired
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

internal class EsiRequestFailed(endpoint: String, status: Int, body: String) :
    Exception("ESI $endpoint answered $status: $body")

/** Returns the body of a successful response; a missing scope asks for a new login, anything else fails with the status. */
internal suspend fun HttpResponse.bodyOrFail(endpoint: String, subject: String): String {
    val body = bodyAsText()
    if (status == HttpStatusCode.Forbidden || status == HttpStatusCode.Unauthorized) {
        throw LoginRequired("EVE denied $subject access. Please use Add character to re-authorize $subject access.")
    }
    if (!status.isSuccess()) throw EsiRequestFailed(endpoint, status.value, body)
    return body
}
