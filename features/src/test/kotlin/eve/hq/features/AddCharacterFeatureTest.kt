package eve.hq.features

import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.LoginFailed
import eve.hq.features.support.FeatureSystem
import eve.hq.features.support.aCharacter
import eve.hq.features.support.aToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddCharacterFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `given no characters, when Alice logs in, the list shows Alice as valid`() = runBlocking {
        system.given { }
        system.sso.next = { aCharacter(id = 1, name = "Alice") }

        system.addCharacter()

        assertEquals(
            listOf("Alice" to CharacterStatus.Valid),
            system.listCharacters().map { it.name to it.status },
        )
    }

    @Test
    fun `given Alice stored with an old token, when Alice logs in again, there is one Alice with the new token`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID").refresh_token("old")
        }
        system.sso.next = { aCharacter(id = 1, name = "Alice", token = aToken(refresh = "new")) }

        system.addCharacter()

        assertEquals(
            listOf("Alice" to "new"),
            system.listCharacters().map { it.name to it.token.refreshToken },
        )
    }

    @Test
    fun `given Alice stored as invalid, when Alice logs in again, Alice is valid`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("INVALID").invalid_reason("token revoked")
        }
        system.sso.next = { aCharacter(id = 1, name = "Alice") }

        system.addCharacter()

        assertEquals(listOf(CharacterStatus.Valid), system.listCharacters().map { it.status })
    }

    @Test
    fun `given Alice stored, when Bob logs in, the list shows Alice and Bob`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID")
        }
        system.sso.next = { aCharacter(id = 2, name = "Bob") }

        system.addCharacter()

        assertEquals(listOf("Alice", "Bob"), system.listCharacters().map { it.name })
    }

    @Test
    fun `given no characters, when the login fails, nothing is stored and the failure is reported`() = runBlocking {
        system.given { }
        system.sso.next = { throw LoginFailed("user cancelled") }

        assertFailsWith<LoginFailed> { system.addCharacter() }

        assertEquals(emptyList(), system.listCharacters())
    }

    @Test
    fun `given Alice stored, when another login fails, Alice is untouched`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID")
        }
        system.sso.next = { throw LoginFailed("denied") }

        assertFailsWith<LoginFailed> { system.addCharacter() }

        assertEquals(listOf("Alice"), system.listCharacters().map { it.name })
    }
}
