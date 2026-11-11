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
}
