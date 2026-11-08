package eve.hq.features

import eve.hq.core.character.CharacterStatus
import eve.hq.features.support.FeatureSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkCharacterInvalidFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `given Alice and Bob valid, when Alice is marked invalid, Alice shows the reason and Bob is unchanged`() {
        system.given {
            characters().id(1).name("Alice").status("VALID")
            characters().id(2).name("Bob").status("VALID")
        }

        system.markInvalid(id = 1, reason = "token revoked")

        assertEquals(
            listOf("Alice" to CharacterStatus.Invalid("token revoked"), "Bob" to CharacterStatus.Valid),
            system.listCharacters().map { it.name to it.status },
        )
    }

    @Test
    fun `given Alice already invalid, when marked invalid again, the latest reason is shown`() {
        system.given {
            characters().id(1).name("Alice").status("INVALID").invalid_reason("token expired")
        }

        system.markInvalid(id = 1, reason = "token revoked")

        assertEquals(
            listOf<Any>(CharacterStatus.Invalid("token revoked")),
            system.listCharacters().map { it.status },
        )
    }

    @Test
    fun `given no such character, when it is marked invalid, nothing changes and nothing fails`() {
        system.given {
            characters().id(1).name("Alice").status("VALID")
        }

        system.markInvalid(id = 42, reason = "token revoked")

        assertEquals(listOf<Any>(CharacterStatus.Valid), system.listCharacters().map { it.status })
    }
}
