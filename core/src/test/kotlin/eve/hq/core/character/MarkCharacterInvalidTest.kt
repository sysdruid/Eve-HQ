package eve.hq.core.character

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkCharacterInvalidTest {
    private val repository = FakeCharacterRepository()

    @Test
    fun `marking a character invalid records the reason`() {
        val alice = aCharacter(id = 1)
        repository.save(alice)

        MarkCharacterInvalid(repository)(1, "token revoked")

        assertEquals(
            listOf(alice.copy(status = CharacterStatus.Invalid("token revoked"))),
            ListCharacters(repository)(),
        )
    }

    @Test
    fun `marking an unknown character invalid does nothing`() {
        MarkCharacterInvalid(repository)(42, "token revoked")

        assertEquals(emptyList(), ListCharacters(repository)())
    }
}
