package eve.hq.data.esi

import eve.hq.data.CachedLocationName
import eve.hq.data.EsiMock
import eve.hq.data.LocationNameCache
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test

class EsiNamesLocationCacheTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val cache = MemoryLocationNameCache()
    private val clock = SteppingClock(now)

    @Test
    fun `a cached name is used without asking ESI`() = runBlocking<Unit> {
        cache.save(60003760, "Jita IV - Moon 4", now.minus(Duration.ofDays(30)))
        val esi = EsiMock { }

        val name = namesFor(esi).stationName(60003760)

        assertThat(name).isEqualTo("Jita IV - Moon 4")
        assertThat(esi.requests).isEmpty()
    }

    @Test
    fun `a resolved name is saved and survives a restart`() = runBlocking<Unit> {
        val first = EsiMock { universeStations(60003760).willReturn("""{"name":"Jita IV - Moon 4"}""") }
        namesFor(first).stationName(60003760)

        val afterRestart = EsiMock { }
        val name = namesFor(afterRestart).stationName(60003760)

        assertThat(name).isEqualTo("Jita IV - Moon 4")
        assertThat(afterRestart.requests).isEmpty()
        assertThat(cache.find(60003760)).isEqualTo(CachedLocationName("Jita IV - Moon 4", now))
    }

    @Test
    fun `a refused lookup is remembered for a day and then tried again`() = runBlocking<Unit> {
        val structure = 1035466617946
        val refused = EsiMock { universeStructures(structure).willReturn("{}", HttpStatusCode.Forbidden) }
        assertThat(namesFor(refused).structureName(structure, "token")).isNull()

        clock.advance(Duration.ofHours(23))
        val sameDay = EsiMock { }
        assertThat(namesFor(sameDay).structureName(structure, "token")).isNull()
        assertThat(sameDay.requests).isEmpty()

        clock.advance(Duration.ofHours(2))
        val nextDay = EsiMock { universeStructures(structure).willReturn("""{"name":"Raitaru of Alice"}""") }
        assertThat(namesFor(nextDay).structureName(structure, "token")).isEqualTo("Raitaru of Alice")
    }

    @Test
    fun `a server error or a network failure is not remembered`() = runBlocking<Unit> {
        val broken = EsiMock {
            universeStations(1).willReturn("{}", HttpStatusCode.InternalServerError)
            universeStations(2).willFail(IOException("offline"))
        }
        val names = namesFor(broken)

        assertThat(names.stationName(1)).isNull()
        assertThat(names.stationName(2)).isNull()

        assertThat(cache.find(1)).isNull()
        assertThat(cache.find(2)).isNull()
    }

    private fun namesFor(esi: EsiMock) = EsiNames(esi.client, esi.baseUrl, null, cache, clock)

    private class MemoryLocationNameCache : LocationNameCache {
        private val names = mutableMapOf<Long, CachedLocationName>()

        override fun find(id: Long) = names[id]

        override fun save(id: Long, name: String?, checkedAt: Instant) {
            names[id] = CachedLocationName(name, checkedAt)
        }
    }

    private class SteppingClock(private var now: Instant) : Clock() {
        fun advance(by: Duration) {
            now += by
        }

        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
