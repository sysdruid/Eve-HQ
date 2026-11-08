package eve.hq.app.characters

import eve.hq.core.character.AddCharacter
import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginFailed
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.RemoveCharacter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CharacterRow(val id: Long, val name: String, val problem: String?)

sealed interface LoginState {
    data object Idle : LoginState
    data object WaitingForBrowser : LoginState
    data class Failed(val message: String) : LoginState
}

data class CharactersUiState(
    val characters: List<CharacterRow> = emptyList(),
    val login: LoginState = LoginState.Idle,
)

class CharactersViewModel(
    private val listCharacters: ListCharacters,
    private val addCharacterUseCase: AddCharacter,
    private val removeCharacterUseCase: RemoveCharacter,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(CharactersUiState())
    val state: StateFlow<CharactersUiState> = _state

    init {
        scope.launch { refresh() }
    }

    private var loginJob: Job? = null

    fun addCharacter() {
        if (loginJob?.isActive == true) return
        loginJob = scope.launch {
            _state.update { it.copy(login = LoginState.WaitingForBrowser) }
            try {
                withContext(io) { addCharacterUseCase() }
                refresh()
                _state.update { it.copy(login = LoginState.Idle) }
            } catch (e: LoginFailed) {
                _state.update { it.copy(login = LoginState.Failed(e.message.orEmpty())) }
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _state.update { it.copy(login = LoginState.Idle) }
    }

    fun remove(id: Long) {
        scope.launch {
            withContext(io) { removeCharacterUseCase(id) }
            refresh()
        }
    }

    private suspend fun refresh() {
        val rows = withContext(io) { listCharacters().map(::toRow) }
        _state.update { it.copy(characters = rows) }
    }

    private fun toRow(character: EveCharacter) = CharacterRow(
        id = character.id,
        name = character.name,
        problem = (character.status as? CharacterStatus.Invalid)?.reason,
    )
}
