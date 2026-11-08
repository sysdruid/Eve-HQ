package eve.hq.core.character

import java.time.Instant

fun aToken(refresh: String = "refresh-1") =
    AuthToken(accessToken = "access", refreshToken = refresh, expiresAt = Instant.parse("2026-01-01T00:00:00Z"))

fun aCharacter(id: Long = 1, name: String = "Alice", token: AuthToken = aToken()) =
    EveCharacter(id = id, name = name, token = token)

class FakeSsoAuthenticator(var result: () -> EveCharacter) : SsoAuthenticator {
    constructor(character: EveCharacter) : this({ character })

    override suspend fun login(): EveCharacter = result()
}
