package eve.hq.core.industry

import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.FreshCharacter
import eve.hq.core.character.RefreshCharacterToken
import java.time.Clock

class RefreshingJobSource(
    repository: CharacterRepository,
    refreshCharacterToken: RefreshCharacterToken,
    clock: Clock,
    private val delegate: JobSource,
) : JobSource {
    private val freshCharacter = FreshCharacter(repository, refreshCharacterToken, clock)

    override suspend fun load(character: EveCharacter): List<IndustryJob> = delegate.load(freshCharacter(character))
}
