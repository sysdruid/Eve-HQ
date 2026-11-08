package eve.hq.core.character

class AddCharacter(
    private val sso: SsoAuthenticator,
    private val repository: CharacterRepository,
) {
    suspend operator fun invoke(): EveCharacter =
        sso.login().also(repository::save)
}
