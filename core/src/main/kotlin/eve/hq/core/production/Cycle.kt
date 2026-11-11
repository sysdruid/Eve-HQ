package eve.hq.core.production

import java.time.Duration

/** The tempo of a production cycle: how long it is, and how many times faster than base time the owner's jobs run. */
data class CycleSettings(val length: Duration, val speed: Double = 1.0) {
    init {
        require(!length.isZero && !length.isNegative) { "A cycle must last longer than zero" }
        require(speed > 0) { "Speed must be positive" }
    }
}
