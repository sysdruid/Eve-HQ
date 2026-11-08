package eve.hq.app.characters

import eve.hq.core.character.AddCharacter
import eve.hq.core.character.AuthToken
import eve.hq.core.character.CharacterRepository
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.RemoveCharacter
import eve.hq.core.character.SsoAuthenticator
import eve.hq.core.character.CharacterStatus
import eve.hq.data.JooqCharacterRepository
import eve.hq.data.Migrations
import eve.hq.data.TokenCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.h2.jdbcx.JdbcDataSource
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** A login the test finishes (or fails) by hand, so the "waiting" state can be observed. */
class ManualSso : SsoAuthenticator {
    private var pending = CompletableDeferred<EveCharacter>()

    var loginsStarted = 0
        private set

    override suspend fun login(): EveCharacter {
        loginsStarted++
        val attempt = pending
        try {
            return attempt.await()
        } finally {
            if (attempt === pending && attempt.isCompleted) pending = CompletableDeferred()
        }
    }

    fun succeed(character: EveCharacter) = pending.complete(character)

    fun fail(cause: Throwable) = pending.completeExceptionally(cause)
}

/** Real use cases over a real in-memory H2; only the SSO boundary is faked. */
class AppSystem(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher) {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)

    val repository: CharacterRepository = JooqCharacterRepository(dataSource, TokenCipher(ByteArray(32).also(SecureRandom()::nextBytes)))
    val sso = ManualSso()

    fun viewModel() = CharactersViewModel(
        ListCharacters(repository),
        AddCharacter(sso, repository),
        RemoveCharacter(repository),
        scope,
        dispatcher,
    )

    fun store(id: Long, name: String, status: CharacterStatus = CharacterStatus.Valid) {
        repository.save(
            EveCharacter(id, name, AuthToken("access", "refresh", Instant.parse("2026-01-01T00:00:00Z")), status),
        )
    }

    fun character(id: Long, name: String) = EveCharacter(id, name, AuthToken("a", "r", Instant.parse("2026-01-01T00:00:00Z")))
}
