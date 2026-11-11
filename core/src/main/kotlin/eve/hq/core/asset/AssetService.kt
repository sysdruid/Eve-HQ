package eve.hq.core.asset

import eve.hq.core.attempt
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.PerCharacterLock
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One character's assets as far as they are known, plus why they could not be refreshed, if they could not. */
data class CharacterAssets(val character: EveCharacter, val snapshot: AssetSnapshot?, val failure: AssetLoadFailure?)

/**
 * Hides when assets are fetched: callers ask for assets and get the last known copy while it is still fresh.
 * The [source] itself honours ESI's exact expiry; [freshFor] only spares it (and the database) repeated calls
 * from several tabs in quick succession.
 */
class AssetService(
    private val source: AssetSource,
    private val clock: Clock,
    private val freshFor: Duration = Duration.ofMinutes(5),
) {
    private val known = ConcurrentHashMap<Long, AssetSnapshot>()
    private val loads = PerCharacterLock()

    suspend fun assetsOf(characters: List<EveCharacter>, refresh: Boolean = false): List<CharacterAssets> =
        characters.map { assetsOf(it, refresh) }

    /** What is known without touching the network: the in-memory copy, else the saved snapshot. */
    fun saved(characters: List<EveCharacter>): List<CharacterAssets> =
        characters.map { CharacterAssets(it, lastKnown(it), null) }

    private suspend fun assetsOf(character: EveCharacter, refresh: Boolean): CharacterAssets =
        loads.withLock(character.id) {
            // Checked inside the lock so a caller that waited for another load finds its result fresh.
            known[character.id]?.takeIf { !refresh && isFresh(it) }?.let { return@withLock CharacterAssets(character, it, null) }
            val result = attempt { source.load(character) }
            result.getOrNull()?.let { items ->
                val snapshot = AssetSnapshot(items, clock.instant())
                known[character.id] = snapshot
                return@withLock CharacterAssets(character, snapshot, null)
            }
            val error = result.exceptionOrNull()
            val failure = AssetLoadFailure(character.id, character.name, error?.message ?: "Unable to load assets")
            CharacterAssets(character, lastKnown(character), failure)
        }

    private fun lastKnown(character: EveCharacter) = known[character.id] ?: source.cachedSnapshot(character.id)

    private fun isFresh(snapshot: AssetSnapshot) = snapshot.updatedAt.plus(freshFor).isAfter(clock.instant())
}
