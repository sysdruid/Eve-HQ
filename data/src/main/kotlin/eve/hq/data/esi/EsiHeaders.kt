package eve.hq.data.esi

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun HttpResponse.expiresAt(): Instant? = headers[HttpHeaders.Expires]?.let { value ->
    runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
}
