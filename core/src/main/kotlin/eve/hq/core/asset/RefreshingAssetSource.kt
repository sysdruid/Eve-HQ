package eve.hq.core.asset

import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.RefreshCharacterToken
import java.time.Clock

class RefreshingAssetSource(
    private val repository: CharacterRepository,
    private val refreshCharacterToken: RefreshCharacterToken,
    private val clock: Clock,
    private val delegate: AssetSource,
) : AssetSource {
    override suspend fun load(character: EveCharacter): AssetInventory {
        var current = repository.find(character.id) ?: error("Character ${character.name} no longer exists")
        if (current.status is CharacterStatus.Invalid) throw needsLogin()
        if (!current.token.expiresAt.isAfter(clock.instant())) {
            refreshCharacterToken(current.id)
            current = repository.find(current.id) ?: error("Character ${character.name} no longer exists")
            if (current.status is CharacterStatus.Invalid) throw needsLogin()
        }
        return delegate.load(current)
    }

    private fun needsLogin() = AssetAccessDenied("EVE authorization is no longer valid. Please use Add character to log in again.")
}