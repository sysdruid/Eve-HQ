package eve.hq.core.character

class RefreshCharacterToken(
    private val repository: CharacterRepository,
    private val refresher: TokenRefresher,
) {
    suspend operator fun invoke(id: Long) {
        val character = repository.find(id) ?: return
        if (character.status is CharacterStatus.Invalid) return
        val refreshed = try {
            refresher.refresh(character.token.refreshToken)
        } catch (e: TokenRevoked) {
            MarkCharacterInvalid(repository)(id, e.message.orEmpty())
            return
        }
        repository.save(character.copy(token = refreshed))
    }
}
