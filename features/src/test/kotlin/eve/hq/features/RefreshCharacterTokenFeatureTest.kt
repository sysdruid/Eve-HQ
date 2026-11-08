package eve.hq.features

import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.TokenRevoked
import eve.hq.features.support.FeatureSystem
import eve.hq.features.support.aToken
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefreshCharacterTokenFeatureTest {
    private val system = FeatureSystem()

    @Test
    fun `given Alice with a refresh token, when it is refreshed, the new tokens are stored, including a rotated refresh token`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID").refresh_token("old")
        }
        system.refresher.next = { old -> aToken(refresh = "rotated-from-$old") }

        system.refreshToken(id = 1)

        assertEquals(listOf("rotated-from-old"), system.listCharacters().map { it.token.refreshToken })
    }

    @Test
    fun `given the SSO no longer accepts the refresh token, Alice is marked invalid with the reason`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID")
            characters().id(2).name("Bob").status("VALID")
        }
        system.refresher.next = { throw TokenRevoked("token revoked") }

        system.refreshToken(id = 1)

        assertEquals(
            listOf("Alice" to CharacterStatus.Invalid("token revoked"), "Bob" to CharacterStatus.Valid),
            system.listCharacters().map { it.name to it.status },
        )
    }

    @Test
    fun `given the SSO cannot be reached, the failure is reported and Alice keeps her old token`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("VALID").refresh_token("old")
        }
        system.refresher.next = { throw IOException("no route to host") }

        assertFailsWith<IOException> { system.refreshToken(id = 1) }

        assertEquals(
            listOf(Triple("Alice", CharacterStatus.Valid, "old")),
            system.listCharacters().map { Triple(it.name, it.status, it.token.refreshToken) },
        )
    }

    @Test
    fun `given an invalid character, no refresh is attempted`() = runBlocking {
        system.given {
            characters().id(1).name("Alice").status("INVALID").invalid_reason("token revoked")
        }

        system.refreshToken(id = 1)

        assertEquals(listOf<Any>(CharacterStatus.Invalid("token revoked")), system.listCharacters().map { it.status })
    }

    @Test
    fun `given no such character, nothing happens`() = runBlocking {
        system.given { }

        system.refreshToken(id = 42)

        assertEquals(emptyList(), system.listCharacters())
    }
}
