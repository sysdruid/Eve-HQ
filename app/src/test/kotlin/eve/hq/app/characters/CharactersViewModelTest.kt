package eve.hq.app.characters

import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.LoginFailed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CharactersViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)

    @Test
    fun `given stored characters, the list shows them A to Z`() {
        system.store(1, "Carol")
        system.store(2, "Alice")

        val viewModel = system.viewModel()

        assertEquals(listOf("Alice", "Carol"), viewModel.state.value.characters.map { it.name })
    }

    @Test
    fun `an invalid character is flagged with its reason`() {
        system.store(1, "Alice")
        system.store(2, "Bob", CharacterStatus.Invalid("token revoked"))

        val viewModel = system.viewModel()

        assertEquals(listOf(null, "token revoked"), viewModel.state.value.characters.map { it.problem })
    }

    @Test
    fun `adding a character waits for the browser, then shows the new character`() {
        val viewModel = system.viewModel()

        viewModel.addCharacter()
        val whileWaiting = viewModel.state.value.login
        system.sso.succeed(system.character(1, "Alice"))

        assertEquals(LoginState.WaitingForBrowser, whileWaiting)
        assertEquals(LoginState.Idle, viewModel.state.value.login)
        assertEquals(listOf("Alice"), viewModel.state.value.characters.map { it.name })
    }

    @Test
    fun `a failed login shows the error and leaves the list unchanged`() {
        system.store(1, "Alice")
        val viewModel = system.viewModel()

        viewModel.addCharacter()
        system.sso.fail(LoginFailed("user cancelled"))

        assertEquals(LoginState.Failed("user cancelled"), viewModel.state.value.login)
        assertEquals(listOf("Alice"), viewModel.state.value.characters.map { it.name })
    }

    @Test
    fun `cancelling while waiting returns to idle, and a new login can start`() {
        val viewModel = system.viewModel()
        viewModel.addCharacter()

        viewModel.cancelLogin()

        assertEquals(LoginState.Idle, viewModel.state.value.login)
        viewModel.addCharacter()
        assertEquals(LoginState.WaitingForBrowser, viewModel.state.value.login)
    }

    @Test
    fun `asking to add while a login is already waiting does not start a second one`() {
        val viewModel = system.viewModel()
        viewModel.addCharacter()

        viewModel.addCharacter()

        assertEquals(1, system.sso.loginsStarted)
    }

    @Test
    fun `removing a character updates the list`() {
        system.store(1, "Alice")
        system.store(2, "Bob")
        val viewModel = system.viewModel()

        viewModel.remove(1)

        assertEquals(listOf("Bob"), viewModel.state.value.characters.map { it.name })
    }

    @Test
    fun `an unexpected failure shows an error instead of waiting forever, and a new login can start`() {
        val viewModel = system.viewModel()
        viewModel.addCharacter()

        system.sso.fail(IllegalStateException("database is locked"))

        assertEquals(LoginState.Failed("Unexpected error: database is locked"), viewModel.state.value.login)
        viewModel.addCharacter()
        assertEquals(LoginState.WaitingForBrowser, viewModel.state.value.login)
    }
}
