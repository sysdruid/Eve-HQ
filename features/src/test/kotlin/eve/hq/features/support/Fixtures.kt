package eve.hq.features.support

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import java.time.Instant

fun aToken(refresh: String = "refresh-1") =
    AuthToken(accessToken = "access", refreshToken = refresh, expiresAt = Instant.parse("2026-01-01T00:00:00Z"))

fun aCharacter(id: Long, name: String, token: AuthToken = aToken()) =
    EveCharacter(id = id, name = name, token = token)
