package eve.hq.core.production

import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.ListAssets
import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSnapshot
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.test.Test

class ListStockpilesTest {
    private val alice = character(1, "Alice")
    private val bob = character(2, "Bob")
    private val crystals = Stockpile(item = 100, name = "Multifrequency L", required = 100, preferred = 300)

    private val stockpiles = mutableListOf<Stockpile>()
    private val repository = object : StockpileRepository {
        override fun all() = stockpiles.toList()

        override fun save(stockpile: Stockpile) = error("not used by listing")

        override fun remove(key: StockpileKey) = error("not used by listing")
    }
    private val assets = mutableMapOf<Long, List<AssetItem>>()

    private val failing = mutableMapOf<Long, String>()
    private val cached = mutableMapOf<Long, List<AssetItem>>()

    private val source = object : AssetSource {
        override suspend fun load(character: EveCharacter): List<AssetItem> =
            failing[character.id]?.let { throw IOException(it) } ?: assets.getValue(character.id)

        override fun cachedSnapshot(characterId: Long) =
            cached[characterId]?.let { AssetSnapshot(it, Instant.parse("2026-11-11T10:00:00Z")) }
    }

    private fun list(vararg characters: EveCharacter) = runBlocking {
        ListStockpiles(repository, source)(characters.toList())
    }

    @Test
    fun `on hand sums an item over all characters and containers`() {
        stockpiles += crystals
        assets[alice.id] = listOf(
            asset(1, crystals.item, 150, location = 9000),
            asset(2, crystals.item, 20, location = 1), // inside the container above
        )
        assets[bob.id] = listOf(asset(3, crystals.item, 40, location = 9000), asset(4, 34, 5_000, location = 9000))

        val listing = list(alice, bob)

        assertThat(listing.rows).containsExactly(StockpileRow(crystals, 210, StockpileStatus.BelowPreferred))
    }

    @Test
    fun `an item nobody owns is zero on hand`() {
        stockpiles += crystals
        assets[alice.id] = emptyList()

        assertThat(list(alice).rows).containsExactly(StockpileRow(crystals, 0, StockpileStatus.BelowRequired))
    }

    @Test
    fun `a character whose assets fail counts from the cache and the failure is reported`() {
        stockpiles += crystals
        assets[bob.id] = listOf(asset(3, crystals.item, 40, location = 9000))
        failing[alice.id] = "ESI down"
        cached[alice.id] = listOf(asset(1, crystals.item, 150, location = 9000))

        val listing = list(alice, bob)

        assertThat(listing.rows.single().onHand).isEqualTo(190)
        assertThat(listing.failures).containsExactly(AssetLoadFailure(alice.id, "Alice", "ESI down"))
    }

    @Test
    fun `a character whose assets fail without a cache counts nothing and the failure is reported`() {
        stockpiles += crystals
        failing[alice.id] = "ESI down"

        val listing = list(alice)

        assertThat(listing.rows.single().onHand).isZero()
        assertThat(listing.failures.map { it.characterName }).containsExactly("Alice")
    }

    @Test
    fun `rows list the worst status first and then by name`() {
        val fuel = Stockpile(item = 200, name = "Fuel blocks", required = 10)
        val ammo = Stockpile(item = 300, name = "Ammo", required = 10)
        val gas = Stockpile(item = 400, name = "Gas", required = 10, overflow = 50)
        stockpiles += listOf(gas, fuel, crystals, ammo)
        assets[alice.id] = listOf(
            asset(1, fuel.item, 10, 9000), // ok
            asset(2, ammo.item, 10, 9000), // ok
            asset(3, gas.item, 99, 9000), // overflow
            asset(4, crystals.item, 150, 9000), // below preferred
        )

        val names = list(alice).rows.map { it.stockpile.name }

        assertThat(names).containsExactly("Multifrequency L", "Gas", "Ammo", "Fuel blocks")
    }

    private fun character(id: Long, name: String) = EveCharacter(id, name, AuthToken("access", "refresh", Instant.MAX))

    private fun asset(itemId: Long, typeId: Long, quantity: Int, location: Long) =
        AssetItem(itemId, AssetType(typeId, null), null, quantity, AssetLocation(location, null))

    @Test
    fun `a stockpile at a station counts only what is there, and each station has its own`() {
        val jita = AssetLocation(60000001, "Jita IV")
        val raitaru = AssetLocation(1_000_000_000_001, "Raitaru")
        val everywhere = Stockpile(item = 34, name = "Tritanium", required = 100)
        val inJita = everywhere.copy(station = jita)
        val inRaitaru = everywhere.copy(station = raitaru, required = 1_000)
        stockpiles += listOf(everywhere, inJita, inRaitaru)
        assets[alice.id] = listOf(
            AssetItem(1, AssetType(34, "Tritanium"), null, 150, jita),
            AssetItem(2, AssetType(34, "Tritanium"), null, 600, raitaru),
            AssetItem(3, AssetType(35, "Pyerite"), null, 9_999, raitaru),
        )

        val rows = list(alice).rows.associate { it.stockpile.station?.name to it.onHand }

        assertThat(rows).containsExactlyInAnyOrderEntriesOf(mapOf(null to 750L, "Jita IV" to 150L, "Raitaru" to 600L))
    }

    @Test
    fun `the listing offers the stations the assets are in`() {
        assets[alice.id] = listOf(
            AssetItem(1, AssetType(34, "Tritanium"), null, 1, AssetLocation(60000001, "Jita IV")),
            AssetItem(2, AssetType(35, "Pyerite"), null, 1, AssetLocation(60000001, "Jita IV")),
        )

        assertThat(list(alice).stations).containsExactly(AssetLocation(60000001, "Jita IV"))
    }

    @Test
    fun `cached lists the saved assets without asking the source`() {
        stockpiles += crystals
        cached[alice.id] = listOf(asset(1, crystals.item, 150, location = 9000))
        failing[alice.id] = "must not be asked"

        val listing = ListStockpiles(repository, source).cached(listOf(alice))

        assertThat(listing?.rows).containsExactly(StockpileRow(crystals, 150, StockpileStatus.BelowPreferred))
        assertThat(listing?.failures).isEmpty()
    }

    @Test
    fun `cached is null when no assets were ever saved`() {
        stockpiles += crystals

        assertThat(ListStockpiles(repository, source).cached(listOf(alice))).isNull()
    }

    @Test
    fun `two consumers of one asset service share a single load`() = runBlocking<Unit> {
        stockpiles += crystals
        assets[alice.id] = listOf(asset(1, crystals.item, 150, location = 9000))
        var loads = 0
        val service = AssetService(
            AssetSource {
                loads++
                delay(10)
                assets.getValue(it.id)
            },
            Clock.systemUTC(),
        )

        val assetTab = async { ListAssets(service)(listOf(alice)) }
        val stockpileTab = async { ListStockpiles(repository, service)(listOf(alice)) }
        awaitAll(assetTab, stockpileTab)

        assertThat(loads).isEqualTo(1)
        assertThat(stockpileTab.await().rows.single().onHand).isEqualTo(150)
    }
}
