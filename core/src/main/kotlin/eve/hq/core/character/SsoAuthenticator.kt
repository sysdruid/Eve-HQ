package eve.hq.core.character

/** Runs an interactive login and returns the authenticated character. */
interface SsoAuthenticator {
    /** @throws LoginFailed if the login was cancelled, denied or could not complete. */
    suspend fun login(): EveCharacter
}

class LoginFailed(message: String, cause: Throwable? = null) : Exception(message, cause)
