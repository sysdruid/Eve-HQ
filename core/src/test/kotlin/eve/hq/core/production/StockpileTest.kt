package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StockpileTest {
    private val crystals = Stockpile(item = 1, name = "Crystals", required = 100, preferred = 300, overflow = 1000)

    @Test
    fun `below the required level is below required`() {
        assertThat(crystals.statusFor(99)).isEqualTo(StockpileStatus.BelowRequired)
    }

    @Test
    fun `exactly the required level is fine when there is no preferred level`() {
        assertThat(Stockpile(1, "Crystals", required = 100).statusFor(100)).isEqualTo(StockpileStatus.Ok)
    }

    @Test
    fun `between required and preferred is below preferred`() {
        assertThat(crystals.statusFor(100)).isEqualTo(StockpileStatus.BelowPreferred)
        assertThat(crystals.statusFor(299)).isEqualTo(StockpileStatus.BelowPreferred)
    }

    @Test
    fun `from preferred up to the overflow level is ok`() {
        assertThat(crystals.statusFor(300)).isEqualTo(StockpileStatus.Ok)
        assertThat(crystals.statusFor(1000)).isEqualTo(StockpileStatus.Ok)
    }

    @Test
    fun `above the overflow level is overflow`() {
        assertThat(crystals.statusFor(1001)).isEqualTo(StockpileStatus.AboveOverflow)
    }

    @Test
    fun `levels that are negative or out of order are rejected`() {
        assertFailsWith<IllegalArgumentException> { Stockpile(1, "Crystals", required = -1) }
        assertFailsWith<IllegalArgumentException> { Stockpile(1, "Crystals", required = 100, preferred = 99) }
        assertFailsWith<IllegalArgumentException> { Stockpile(1, "Crystals", required = 100, preferred = 300, overflow = 299) }
        assertFailsWith<IllegalArgumentException> { Stockpile(1, "Crystals", required = 100, overflow = 99) }
    }
}
