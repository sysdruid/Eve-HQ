package eve.hq.core.character

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one character's loads from running at the same time. Two tabs asking for the same character would otherwise
 * both replace its cached pages and collide on the primary key; queued, the second finds a fresh cache.
 */
class PerCharacterLock {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(characterId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(characterId) { Mutex() }.withLock { block() }
}
