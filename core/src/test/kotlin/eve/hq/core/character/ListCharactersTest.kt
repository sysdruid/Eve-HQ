package eve.hq.core.character

import kotlin.test.Test
import kotlin.test.assertEquals

class ListCharactersTest {
    private val repository = FakeCharacterRepository()

    @Test
    fun `lists nothing when no characters were added`() {
        assertEquals(emptyList(), ListCharacters(repository)())
    }
}
