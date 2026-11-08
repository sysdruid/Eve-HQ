package eve.hq.data.sso

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64

data class TokenClaims(val characterId: Long, val characterName: String, val expiresAt: Instant)

class MalformedToken(message: String) : Exception(message)

object AccessToken {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Payload(val sub: String, val name: String, val exp: Long)

    fun claims(jwt: String): TokenClaims {
        val parts = jwt.split(".")
        if (parts.size != 3) throw MalformedToken("expected 3 dot-separated parts, got ${parts.size}")
        val payload = try {
            json.decodeFromString<Payload>(String(Base64.getUrlDecoder().decode(parts[1])))
        } catch (e: IllegalArgumentException) {
            throw MalformedToken("payload is not valid: ${e.message}")
        }
        val id = payload.sub.removePrefix(CHARACTER_PREFIX).takeIf { payload.sub.startsWith(CHARACTER_PREFIX) }
            ?.toLongOrNull() ?: throw MalformedToken("subject is not a character: ${payload.sub}")
        return TokenClaims(characterId = id, characterName = payload.name, expiresAt = Instant.ofEpochSecond(payload.exp))
    }

    private const val CHARACTER_PREFIX = "CHARACTER:EVE:"
}
