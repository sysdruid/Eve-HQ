package eve.hq.core.asset

import eve.hq.core.character.EveCharacter
import eve.hq.core.character.FreshCharacter
import eve.hq.core.character.PerCharacterLock
import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.RefreshCharacterToken
import java.time.Clock

class RefreshingAssetSource(
    repository: CharacterRepository,
    refreshCharacterToken: RefreshCharacterToken,
    clock: Clock,
    private val delegate: AssetSource,
) : AssetSource {
    private val freshCharacter = FreshCharacter(repository, refreshCharacterToken, clock)
    private val loads = PerCharacterLock()

    override fun cachedSnapshot(characterId: Long) = delegate.cachedSnapshot(characterId)

    override suspend fun load(character: EveCharacter): List<AssetItem> =
        loads.withLock(character.id) { delegate.load(freshCharacter(character)) }
}
