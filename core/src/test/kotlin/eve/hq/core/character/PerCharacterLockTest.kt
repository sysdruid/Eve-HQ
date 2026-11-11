package eve.hq.core.character

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class PerCharacterLockTest {
    private val lock = PerCharacterLock()
    private val running = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    private suspend fun work(characterId: Long) = lock.withLock(characterId) {
        mostAtOnce.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        delay(20)
        running.decrementAndGet()
    }

    @Test
    fun `work for the same character never overlaps`() = runBlocking<Unit> {
        List(4) { async { work(1) } }.awaitAll()

        assertThat(mostAtOnce.get()).isEqualTo(1)
    }

    @Test
    fun `work for different characters still runs side by side`() = runBlocking<Unit> {
        List(3) { id -> async { work(id.toLong()) } }.awaitAll()

        assertThat(mostAtOnce.get()).isGreaterThan(1)
    }
}
