package eve.hq.core.character

import java.time.Instant

data class AuthToken(val accessToken: String, val refreshToken: String, val expiresAt: Instant)

sealed interface CharacterStatus {
    data object Valid : CharacterStatus
    data class Invalid(val reason: String) : CharacterStatus
}

data class EveCharacter(
    val id: Long,
    val name: String,
    val token: AuthToken,
    val status: CharacterStatus = CharacterStatus.Valid,
)
