package eve.hq.core.production

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/** The tempo of a production cycle: how long it is, and how many times faster than base time the owner's jobs run. */
data class CycleSettings(val length: Duration, val speed: Double = 1.0) {
    init {
        require(!length.isZero && !length.isNegative) { "A cycle must last longer than zero" }
        require(speed > 0) { "Speed must be positive" }
    }

    /** Seconds one run really takes: the blueprint's base time, sped up. */
    fun effectiveSecondsPerRun(baseSeconds: Int): Int =
        (BigDecimal(baseSeconds) / BigDecimal(speed.toString())).setScale(0, RoundingMode.HALF_UP).toInt()

    /** The most runs one job may have and still end within the cycle; a single run longer than the cycle is still one. */
    fun maxRunsPerJob(baseSeconds: Int): Long {
        val fitting = (BigDecimal(length.seconds) * BigDecimal(speed.toString())).divideToIntegralValue(BigDecimal(baseSeconds))
        return maxOf(1L, fitting.toLong())
    }
}
