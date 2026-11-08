package eve.hq.core.asset

import eve.hq.core.character.AuthToken
import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.CharacterStatus
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.RefreshCharacterToken
import eve.hq.core.character.TokenRefresher
import eve.hq.core.character.TokenRevoked
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefreshingAssetSourceTest {
    @Test
    fun `an expired character token is refreshed before assets are requested`() = kotlinx.coroutines.runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val character = EveCharacter(1, "Alice", AuthToken("old-access", "refresh", now.minusSeconds(1)))
        val repository = MemoryCharacterRepository(character)
        val refresh = RefreshCharacterToken(repository, object : TokenRefresher {
            override suspend fun refresh(refreshToken: String) = AuthToken("new-access", "rotated", now.plusSeconds(3600))
        })
        var accessTokenUsed: String? = null
        val delegate = AssetSource { requestedCharacter ->
            accessTokenUsed = requestedCharacter.token.accessToken
            emptyList()
        }
        val source = RefreshingAssetSource(
            repository,
            refresh,
            Clock.fixed(now, ZoneOffset.UTC),
            delegate,
        )

        source.load(character)

        assertEquals("new-access", accessTokenUsed)
        assertEquals("rotated", repository.find(1)?.token?.refreshToken)
    }

    @Test
    fun `a revoked refresh token is reported with a login hint`() = kotlinx.coroutines.runBlocking {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val character = EveCharacter(1, "Alice", AuthToken("old-access", "refresh", now.minusSeconds(1)))
        val repository = MemoryCharacterRepository(character)
        val refresh = RefreshCharacterToken(repository, object : TokenRefresher {
            override suspend fun refresh(refreshToken: String): AuthToken = throw TokenRevoked("revoked")
        })
        val source = RefreshingAssetSource(
            repository,
            refresh,
            Clock.fixed(now, ZoneOffset.UTC),
        ) { error("asset request must not run") }

        val result = ListAssets(source)(listOf(character))

        assertTrue(result.failures.single().message.contains("log in again", ignoreCase = true))
        assertTrue(repository.find(1)?.status is CharacterStatus.Invalid)
    }

    @Test
    fun `cached snapshots are forwarded without refreshing character credentials`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val character = EveCharacter(1, "Alice", AuthToken("old-access", "refresh", now.minusSeconds(1)))
        val repository = MemoryCharacterRepository(character)
        val snapshot = AssetSnapshot(
            listOf(AssetItem(101, AssetType(34, "Tritanium"), null, 42, AssetLocation(600, "Jita"))),
            now,
        )
        val delegate = object : AssetSource {
            override suspend fun load(character: EveCharacter): List<AssetItem> = error("asset request must not run")
            override fun cachedSnapshot(characterId: Long): AssetSnapshot = snapshot
        }
        val source = RefreshingAssetSource(
            repository,
            RefreshCharacterToken(repository, object : TokenRefresher {
                override suspend fun refresh(refreshToken: String): AuthToken = error("token must not be refreshed")
            }),
            Clock.fixed(now, ZoneOffset.UTC),
            delegate,
        )

        assertEquals(snapshot, source.cachedSnapshot(1))
    }

    private class MemoryCharacterRepository(character: EveCharacter) : CharacterRepository {
        private val characters = mutableMapOf(character.id to character)

        override fun all() = characters.values.toList()
        override fun find(id: Long) = characters[id]
        override fun save(character: EveCharacter) {
            characters[character.id] = character
        }

        override fun remove(id: Long) {
            characters.remove(id)
        }
    }
}