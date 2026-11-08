package eve.hq.core.character

class RemoveCharacter(private val repository: CharacterRepository) {
    operator fun invoke(id: Long) = repository.remove(id)
}
