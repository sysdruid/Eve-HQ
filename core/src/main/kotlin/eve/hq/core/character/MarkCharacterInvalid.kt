package eve.hq.core.character

class MarkCharacterInvalid(private val repository: CharacterRepository) {
    operator fun invoke(id: Long, reason: String) {
        repository.find(id)?.let { character ->
            repository.save(character.copy(status = CharacterStatus.Invalid(reason)))
        }
    }
}
