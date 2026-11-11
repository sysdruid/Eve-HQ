package eve.hq.core.asset

import eve.hq.core.attempt
import eve.hq.core.character.EveCharacter
import eve.hq.core.character.PerCharacterLock
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One character's assets as far as they are known, plus why they could not be refreshed, if they could not. */
data class CharacterAssets(val character: EveCharacter, val snapshot: AssetSnapshot?, val failure: AssetLoadFailure?)

/** An asset with its owner, station and containers resolved (outermost container first). */
data class OwnedAsset(
    val characterId: Long,
    val characterName: String,
    val itemId: Long,
    val type: AssetType,
    val customName: String?,
    val quantity: Int,
    val station: AssetLocation,
    val containers: List<String>,
)

/** Narrows [AssetService.assets]; an empty [types] set means any type, a null [stationId] any station. */
data class AssetQuery(val types: Set<Long> = emptySet(), val stationId: Long? = null)

data class OwnedAssets(
    val items: List<OwnedAsset>,
    val failures: List<AssetLoadFailure>,
    val snapshots: List<AssetSnapshotInfo>,
) {
    /** Total quantity per type id over every character and container. */
    fun quantities(): Map<Long, Long> =
        items.groupBy { it.type.id }.mapValues { (_, owned) -> owned.sumOf { it.quantity.toLong() } }
}

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

    suspend fun assets(characters: List<EveCharacter>, query: AssetQuery = AssetQuery(), refresh: Boolean = false) =
        owned(assetsOf(characters, refresh), query)

    /** What is known without touching the network: the in-memory copy, else the saved snapshot. */
    fun saved(characters: List<EveCharacter>): List<CharacterAssets> =
        characters.map { CharacterAssets(it, lastKnown(it), null) }

    /** [saved], resolved and filtered like [assets]; characters with nothing saved contribute nothing. */
    fun savedAssets(characters: List<EveCharacter>, query: AssetQuery = AssetQuery()): OwnedAssets =
        owned(saved(characters), query)

    private fun owned(results: List<CharacterAssets>, query: AssetQuery) = OwnedAssets(
        items = results.flatMap { result -> result.snapshot?.let { resolve(result.character, it.items) }.orEmpty() }
            .filter { (query.types.isEmpty() || it.type.id in query.types) && (query.stationId == null || it.station.id == query.stationId) },
        failures = results.mapNotNull(CharacterAssets::failure),
        snapshots = results.mapNotNull { result ->
            result.snapshot?.let { AssetSnapshotInfo(result.character.id, result.character.name, it.updatedAt) }
        },
    )

    private fun resolve(character: EveCharacter, items: List<AssetItem>): List<OwnedAsset> {
        val itemsById = items.associateBy(AssetItem::itemId)
        return items.map { item ->
            val containers = mutableListOf<String>()
            val visited = mutableSetOf(item.itemId)
            var location = item.location
            while (true) {
                val parent = itemsById[location.id]?.takeIf { visited.add(it.itemId) } ?: break
                containers += parent.customName ?: parent.type.name ?: "Unknown type (${parent.type.id})"
                location = parent.location
            }
            OwnedAsset(
                character.id, character.name, item.itemId, item.type, item.customName, item.quantity,
                station = location,
                containers = containers.asReversed(),
            )
        }
    }

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
