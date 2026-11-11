package eve.hq.core.blueprint

import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.FreshCharacter
import eve.hq.core.character.PerCharacterLock
import eve.hq.core.character.RefreshCharacterToken
import java.time.Clock

class RefreshingBlueprintSource(
    repository: CharacterRepository,
    refreshCharacterToken: RefreshCharacterToken,
    clock: Clock,
    private val delegate: BlueprintSource,
) : BlueprintSource {
    private val freshCharacter = FreshCharacter(repository, refreshCharacterToken, clock)
    private val loads = PerCharacterLock()

    override suspend fun load(character: EveCharacter): List<OwnedBlueprint> =
        loads.withLock(character.id) { delegate.load(freshCharacter(character)) }

    override fun cached(character: EveCharacter) = delegate.cached(character)
}
