package eve.hq.core.production

import java.time.Duration

/**
 * A production cycle: how long it is, and the [window] at its end that no job may use. How long a job really takes is the
 * business of [RunTimes], not of the cycle.
 */
data class CycleSettings(val length: Duration, val window: Duration = Duration.ZERO) {
    init {
        require(!length.isZero && !length.isNegative) { "A cycle must last longer than zero" }
        require(!window.isNegative && window < length) { "The window must be shorter than the cycle" }
    }

    /** What jobs may use: the cycle minus the [window] kept free so the owner can get to the game and restart them. */
    val usable: Duration get() = length - window
}
