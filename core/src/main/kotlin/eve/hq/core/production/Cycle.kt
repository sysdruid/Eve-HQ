package eve.hq.core.production

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * The tempo of a production cycle: how long it is, how many times faster than base time the owner's jobs run, and the
 * [window] at its end that no job may use.
 */
data class CycleSettings(val length: Duration, val speed: Double = 1.0, val window: Duration = Duration.ZERO) {
    init {
        require(!length.isZero && !length.isNegative) { "A cycle must last longer than zero" }
        require(speed > 0) { "Speed must be positive" }
        require(!window.isNegative && window < length) { "The window must be shorter than the cycle" }
    }

    /** What jobs may use: the cycle minus the [window] kept free so the owner can get to the game and restart them. */
    val usable: Duration get() = length - window

    /** Seconds one run really takes: the blueprint's base time, sped up. */
    fun effectiveSecondsPerRun(baseSeconds: Int): Int =
        (BigDecimal(baseSeconds) / BigDecimal(speed.toString())).setScale(0, RoundingMode.HALF_UP).toInt()

    /** The exact seconds [runs] runs take, rounded once at the end, not per run. */
    fun effectiveSeconds(baseSeconds: Int, runs: Long): Long =
        (BigDecimal(baseSeconds) * BigDecimal(runs) / BigDecimal(speed.toString())).setScale(0, RoundingMode.HALF_UP).toLong()

    /** The most runs one job may have and still end within the cycle; a single run longer than the cycle is still one. */
    fun maxRunsPerJob(baseSeconds: Int): Long {
        val fitting = (BigDecimal(usable.seconds) * BigDecimal(speed.toString())).divideToIntegralValue(BigDecimal(baseSeconds))
        return maxOf(1L, fitting.toLong())
    }
}
