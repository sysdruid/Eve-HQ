package eve.hq.core.character

interface CharacterRepository {
    fun all(): List<EveCharacter>
    fun find(id: Long): EveCharacter?
    fun save(character: EveCharacter)
    fun remove(id: Long)
}
