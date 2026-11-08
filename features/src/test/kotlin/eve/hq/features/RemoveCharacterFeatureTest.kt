package eve.hq.features

import eve.hq.features.support.FeatureSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoveCharacterFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `given Alice and Bob, when Alice is removed, only Bob remains`() {
        system.given {
            characters().id(1).name("Alice").status("VALID")
            characters().id(2).name("Bob").status("VALID")
        }

        system.removeCharacter(id = 1)

        assertEquals(listOf("Bob"), system.listCharacters().map { it.name })
    }

    @Test
    fun `given Alice, when an unknown id is removed, Alice remains and nothing fails`() {
        system.given {
            characters().id(1).name("Alice").status("VALID")
        }

        system.removeCharacter(id = 42)

        assertEquals(listOf("Alice"), system.listCharacters().map { it.name })
    }
}
