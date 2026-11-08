package eve.hq.data.sso

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.LoginFailed
import eve.hq.core.character.SsoAuthenticator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.IOException
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class EveSsoAuthenticator(
    private val config: SsoConfig,
    private val tokens: SsoTokenClient,
    private val openBrowser: (URI) -> Unit,
    private val timeout: Duration = 5.minutes,
) : SsoAuthenticator {
    override suspend fun login(): EveCharacter {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newVerifier()
        val code = CallbackServer.start(config.callbackPort, state).use { callback ->
            val authorizeUrl = AuthorizeUrl.build(config, Pkce.challengeFor(verifier), state)
            try {
                openBrowser(authorizeUrl)
            } catch (e: IOException) {
                throw LoginFailed("could not open a browser: ${e.message}", e)
            } catch (e: UnsupportedOperationException) {
                throw LoginFailed("could not open a browser: ${e.message ?: "not supported on this system"}", e)
            }
            callback.awaitCode(timeout)
        }
        try {
            val token = tokens.exchange(code, verifier)
            val claims = AccessToken.claims(token.accessToken)
            return EveCharacter(
                id = claims.characterId,
                name = claims.characterName,
                token = AuthToken(token.accessToken, token.refreshToken, claims.expiresAt),
            )
        } catch (e: InvalidGrant) {
            throw LoginFailed("EVE SSO rejected the login: ${e.message}", e)
        } catch (e: SsoError) {
            throw LoginFailed("EVE SSO is unavailable (${e.status})", e)
        } catch (e: MalformedToken) {
            throw LoginFailed("EVE SSO returned an unusable token: ${e.message}", e)
        } catch (e: IOException) {
            throw LoginFailed("could not reach EVE SSO: ${e.message}", e)
        }
    }

    companion object {
        fun create(config: SsoConfig, openBrowser: (URI) -> Unit): EveSsoAuthenticator =
            EveSsoAuthenticator(config, SsoTokenClient(HttpClient(CIO), config), openBrowser)
    }
}
