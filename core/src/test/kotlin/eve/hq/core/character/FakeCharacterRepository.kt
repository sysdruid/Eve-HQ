package eve.hq.core.character

class FakeCharacterRepository : CharacterRepository {
    private val characters = linkedMapOf<Long, EveCharacter>()

    override fun all(): List<EveCharacter> = characters.values.toList()

    override fun find(id: Long): EveCharacter? = characters[id]

    override fun save(character: EveCharacter) {
        characters[character.id] = character
    }

    override fun remove(id: Long) {
        characters.remove(id)
    }
}
