package eve.hq.core.character

import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.RefreshingAssetSource
import eve.hq.core.blueprint.BlueprintSource
import eve.hq.core.blueprint.OwnedBlueprint
import eve.hq.core.blueprint.RefreshingBlueprintSource
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.RefreshingJobSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/** Two tabs load the same character at once; the page caches underneath cannot take two writers. */
class RefreshingSourcesTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", now.plusSeconds(3600)))
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = object : CharacterRepository {
        override fun all() = listOf(alice)
        override fun find(id: Long) = alice.takeIf { it.id == id }
        override fun save(character: EveCharacter) = Unit
        override fun remove(id: Long) = Unit
    }
    private val refresh = RefreshCharacterToken(repository, object : TokenRefresher {
        override suspend fun refresh(refreshToken: String): AuthToken = error("token is still valid")
    })

    private val running = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    private suspend fun <T> slow(result: T): T {
        mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        delay(20)
        running.decrementAndGet()
        return result
    }

    private fun loadTwiceAtOnce(load: suspend () -> Unit) = runBlocking<Unit> {
        List(2) { async { load() } }.awaitAll()
        assertThat(mostAtOnce.get()).isEqualTo(1)
    }

    @Test
    fun `asset loads of one character do not overlap`() {
        val source = RefreshingAssetSource(repository, refresh, clock, AssetSource { slow(emptyList<AssetItem>()) })

        loadTwiceAtOnce { source.load(alice) }
    }

    @Test
    fun `job loads of one character do not overlap`() {
        val source = RefreshingJobSource(repository, refresh, clock, JobSource { slow(emptyList<IndustryJob>()) })

        loadTwiceAtOnce { source.load(alice) }
    }

    @Test
    fun `blueprint loads of one character do not overlap`() {
        val source = RefreshingBlueprintSource(
            repository,
            refresh,
            clock,
            BlueprintSource { slow(emptyList<OwnedBlueprint>()) },
        )

        loadTwiceAtOnce { source.load(alice) }
    }
}
