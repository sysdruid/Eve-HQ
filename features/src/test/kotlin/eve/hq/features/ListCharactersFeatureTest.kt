package eve.hq.features

import eve.hq.core.character.CharacterStatus
import eve.hq.features.support.FeatureSystem
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals

class ListCharactersFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `given no characters, the list is empty`() {
        system.given { }

        assertThat(system.listCharacters()).isEmpty()
    }

    @Test
    fun `given characters stored in any order, the list is A to Z by name`() {
        system.given {
            characters().id(1).name("Carol").status("VALID")
            characters().id(2).name("Alice").status("VALID")
            characters().id(3).name("Bob").status("VALID")
        }

        assertEquals(listOf("Alice", "Bob", "Carol"), system.listCharacters().map { it.name })
    }

    @Test
    fun `sorting by name ignores case`() {
        system.given {
            characters().id(1).name("Bob").status("VALID")
            characters().id(2).name("alice").status("VALID")
        }

        assertEquals(listOf("alice", "Bob"), system.listCharacters().map { it.name })
    }

    @Test
    fun `an invalid character is listed with its status and reason`() {
        system.given {
            characters().id(1).name("Alice").status("VALID")
            characters().id(2).name("Bob").status("INVALID").invalid_reason("token revoked")
        }

        assertEquals(
            listOf(CharacterStatus.Valid, CharacterStatus.Invalid("token revoked")),
            system.listCharacters().map { it.status },
        )
    }
}
