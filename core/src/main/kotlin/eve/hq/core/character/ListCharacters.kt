package eve.hq.core.character

class ListCharacters(private val repository: CharacterRepository) {
    operator fun invoke(): List<EveCharacter> = repository.all().sortedBy { it.name.lowercase() }
}
