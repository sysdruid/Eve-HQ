package eve.hq.core.character

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoveCharacterTest {
    private val repository = FakeCharacterRepository()

    @Test
    fun `a removed character disappears from the list`() {
        val alice = aCharacter(id = 1, name = "Alice")
        val bob = aCharacter(id = 2, name = "Bob")
        repository.save(alice)
        repository.save(bob)

        RemoveCharacter(repository)(1)

        assertEquals(listOf(bob), ListCharacters(repository)())
    }

    @Test
    fun `removing an unknown character does nothing`() {
        val alice = aCharacter(id = 1)
        repository.save(alice)

        RemoveCharacter(repository)(42)

        assertEquals(listOf(alice), ListCharacters(repository)())
    }
}
