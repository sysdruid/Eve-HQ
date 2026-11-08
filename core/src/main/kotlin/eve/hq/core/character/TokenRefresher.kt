package eve.hq.core.character

interface TokenRefresher {
    /** @throws TokenRevoked if the refresh token is no longer accepted. */
    suspend fun refresh(refreshToken: String): AuthToken
}

class TokenRevoked(message: String) : Exception(message)
