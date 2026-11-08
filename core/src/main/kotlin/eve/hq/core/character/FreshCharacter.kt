package eve.hq.core.character

import java.time.Clock

class LoginRequired(message: String) : Exception(message)

/** Returns the stored character with a usable access token, refreshing it when it has expired. */
class FreshCharacter(
    private val repository: CharacterRepository,
    private val refreshCharacterToken: RefreshCharacterToken,
    private val clock: Clock,
) {
    suspend operator fun invoke(character: EveCharacter): EveCharacter {
        var current = stored(character)
        if (!current.token.expiresAt.isAfter(clock.instant())) {
            refreshCharacterToken(current.id)
            current = stored(character)
        }
        return current
    }

    private fun stored(character: EveCharacter): EveCharacter {
        val current = repository.find(character.id) ?: error("Character ${character.name} no longer exists")
        if (current.status is CharacterStatus.Invalid) {
            throw LoginRequired("EVE authorization is no longer valid. Please use Add character to log in again.")
        }
        return current
    }
}
