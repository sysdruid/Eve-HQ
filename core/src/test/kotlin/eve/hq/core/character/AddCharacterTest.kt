package eve.hq.core.character

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddCharacterTest {
    private val repository = FakeCharacterRepository()

    @Test
    fun `an added character appears in the list`() = runBlocking {
        val alice = aCharacter(id = 1, name = "Alice")

        AddCharacter(FakeSsoAuthenticator(alice), repository)()

        assertEquals(listOf(alice), ListCharacters(repository)())
    }

    @Test
    fun `a failed login adds nothing and reports the failure`() = runBlocking {
        val sso = FakeSsoAuthenticator { throw LoginFailed("user cancelled") }

        assertFailsWith<LoginFailed> { AddCharacter(sso, repository)() }

        assertEquals(emptyList(), ListCharacters(repository)())
    }

    @Test
    fun `adding the same character again replaces its tokens and makes it valid`() = runBlocking {
        val alice = aCharacter(id = 1, token = aToken(refresh = "old"))
        repository.save(alice.copy(status = CharacterStatus.Invalid("token revoked")))
        val reloggedIn = alice.copy(token = aToken(refresh = "new"))

        AddCharacter(FakeSsoAuthenticator(reloggedIn), repository)()

        assertEquals(listOf(reloggedIn), ListCharacters(repository)())
    }
}
