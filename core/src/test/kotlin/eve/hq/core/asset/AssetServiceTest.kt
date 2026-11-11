package eve.hq.core.asset

import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test

class AssetServiceTest {
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val start = Instant.parse("2026-11-11T12:00:00Z")
    private var now = start
    private val clock = object : Clock() {
        override fun getZone() = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private var loads = 0
    private var assets = listOf(asset(1, 34, 100))
    private var failure: Exception? = null
    private var saved: AssetSnapshot? = null
    private val source = object : AssetSource {
        override suspend fun load(character: EveCharacter): List<AssetItem> {
            loads++
            delay(10)
            failure?.let { throw it }
            return assets
        }

        override fun cachedSnapshot(characterId: Long) = saved
    }
    private val service = AssetService(source, clock, Duration.ofMinutes(5))

    private fun asset(itemId: Long, type: Long, quantity: Int) =
        AssetItem(itemId, AssetType(type, null), null, quantity, AssetLocation(60000001, "Jita"))

    private fun assetsOfAlice(refresh: Boolean = false) = runBlocking { service.assetsOf(listOf(alice), refresh).single() }

    @Test
    fun `assets are loaded from the source and returned with the time they were obtained`() {
        val result = assetsOfAlice()

        assertThat(result.snapshot).isEqualTo(AssetSnapshot(assets, start))
        assertThat(result.failure).isNull()
    }

    @Test
    fun `within the fresh window the source is not asked again`() {
        assetsOfAlice()
        now = start.plusSeconds(299)

        assetsOfAlice()

        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `after the window the source is asked again`() {
        assetsOfAlice()
        assets = listOf(asset(1, 34, 250))
        now = start.plusSeconds(300)

        val result = assetsOfAlice()

        assertThat(loads).isEqualTo(2)
        assertThat(result.snapshot?.items).isEqualTo(assets)
    }

    @Test
    fun `an explicit refresh ignores freshness`() {
        assetsOfAlice()

        assetsOfAlice(refresh = true)

        assertThat(loads).isEqualTo(2)
    }

    @Test
    fun `a failed refresh keeps the last known assets and reports the failure`() {
        assetsOfAlice()
        now = start.plusSeconds(600)
        failure = IOException("ESI down")

        val result = assetsOfAlice()

        assertThat(result.snapshot).isEqualTo(AssetSnapshot(listOf(asset(1, 34, 100)), start))
        assertThat(result.failure).isEqualTo(AssetLoadFailure(1, "Alice", "ESI down"))
    }

    @Test
    fun `a failure before anything was loaded falls back to the saved snapshot, or to nothing`() {
        failure = IOException("ESI down")
        assertThat(assetsOfAlice().snapshot).isNull()

        saved = AssetSnapshot(listOf(asset(9, 35, 5)), start.minusSeconds(3600))
        val result = assetsOfAlice()

        assertThat(result.snapshot).isEqualTo(saved)
        assertThat(result.failure?.message).isEqualTo("ESI down")
    }

    @Test
    fun `saved shows what is known without calling the source`() {
        saved = AssetSnapshot(listOf(asset(9, 35, 5)), start.minusSeconds(3600))
        assertThat(service.saved(listOf(alice)).single().snapshot).isEqualTo(saved)

        assetsOfAlice()

        assertThat(service.saved(listOf(alice)).single().snapshot).isEqualTo(AssetSnapshot(assets, start))
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `simultaneous requests for one character reach the source once`() = runBlocking<Unit> {
        List(3) { async { service.assetsOf(listOf(alice)) } }.awaitAll()

        assertThat(loads).isEqualTo(1)
    }

    private fun item(itemId: Long, type: Long, name: String?, quantity: Int, parent: Long, parentName: String? = null, custom: String? = null) =
        AssetItem(itemId, AssetType(type, name), custom, quantity, AssetLocation(parent, parentName))

    @Test
    fun `assets carry their station and the containers around them, outermost first`() = runBlocking<Unit> {
        assets = listOf(
            item(700, 100, "Large Container", 1, 60000001, "Jita IV"),
            item(701, 200, "Small Container", 1, 700, custom = "Ammo box"),
            item(702, 34, "Tritanium", 42, 701),
        )

        val owned = service.assets(listOf(alice)).items

        assertThat(owned.map { it.type.name }).containsExactly("Large Container", "Small Container", "Tritanium")
        val tritanium = owned.last()
        assertThat(tritanium.station).isEqualTo(AssetLocation(60000001, "Jita IV"))
        assertThat(tritanium.containers).containsExactly("Large Container", "Ammo box")
        assertThat(tritanium.characterName).isEqualTo("Alice")
        assertThat(owned.first().containers).isEmpty()
    }

    @Test
    fun `a query narrows by type and by station`() = runBlocking<Unit> {
        assets = listOf(
            item(1, 34, "Tritanium", 10, 60000001, "Jita IV"),
            item(2, 35, "Pyerite", 20, 60000001, "Jita IV"),
            item(3, 34, "Tritanium", 30, 60000002, "Amarr VIII"),
        )

        assertThat(service.assets(listOf(alice), AssetQuery(types = setOf(34))).items.map { it.itemId }).containsExactly(1L, 3L)
        assertThat(service.assets(listOf(alice), AssetQuery(stationId = 60000002)).items.map { it.itemId }).containsExactly(3L)
        assertThat(service.assets(listOf(alice), AssetQuery(types = setOf(35), stationId = 60000002)).items).isEmpty()
    }

    @Test
    fun `the result lists the failures and snapshot times beside the assets`() = runBlocking<Unit> {
        failure = IOException("ESI down")
        saved = AssetSnapshot(listOf(item(9, 35, "Pyerite", 5, 60000001, "Jita IV")), start.minusSeconds(3600))

        val result = service.assets(listOf(alice))

        assertThat(result.items.map { it.itemId }).containsExactly(9L)
        assertThat(result.failures.single().message).isEqualTo("ESI down")
        assertThat(result.snapshots).containsExactly(AssetSnapshotInfo(1, "Alice", start.minusSeconds(3600)))
    }

    @Test
    fun `a quantity adds a type up over every container and character, optionally at one station`() = runBlocking<Unit> {
        val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
        assets = listOf(
            item(700, 100, "Container", 1, 60000001, "Jita IV"),
            item(701, 34, "Tritanium", 40, 700),
            item(702, 34, "Tritanium", 2_000_000_000, 60000001, "Jita IV"),
            item(703, 34, "Tritanium", 7, 60000002, "Amarr VIII"),
        )

        val owned = service.assets(listOf(alice, bob))

        assertThat(owned.quantityOf(34)).isEqualTo(2 * (2_000_000_000L + 47))
        assertThat(owned.quantityOf(34, stationId = 60000001)).isEqualTo(2 * (2_000_000_000L + 40))
        assertThat(owned.quantityOf(35)).isZero()
    }

    @Test
    fun `the stations known from the assets are listed once each by name`() = runBlocking<Unit> {
        assets = listOf(
            item(1, 34, "Tritanium", 1, 60000002, "Jita IV"),
            item(2, 34, "Tritanium", 1, 60000002, "Jita IV"),
            item(3, 34, "Tritanium", 1, 60000001, "Amarr VIII"),
            item(4, 34, "Tritanium", 1, 1_000_000_000_001),
        )

        val stations = service.assets(listOf(alice)).stations()

        assertThat(stations).containsExactly(
            AssetLocation(60000001, "Amarr VIII"),
            AssetLocation(60000002, "Jita IV"),
            AssetLocation(1_000_000_000_001, null),
        )
    }
}
