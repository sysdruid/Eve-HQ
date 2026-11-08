package eve.hq.data.sso

import eve.hq.core.character.AuthToken
import eve.hq.core.character.TokenRefresher
import eve.hq.core.character.TokenRevoked

class EveTokenRefresher(private val tokens: SsoTokenClient) : TokenRefresher {
    override suspend fun refresh(refreshToken: String): AuthToken {
        val response = try {
            tokens.refresh(refreshToken)
        } catch (e: InvalidGrant) {
            throw TokenRevoked(e.message.orEmpty())
        }
        return AuthToken(response.accessToken, response.refreshToken, AccessToken.claims(response.accessToken).expiresAt)
    }
}
