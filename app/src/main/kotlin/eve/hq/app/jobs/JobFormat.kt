package eve.hq.app.jobs

import eve.hq.core.industry.IndustryActivity
import java.time.Duration

internal fun Duration.asEtl(): String = when {
    toDays() > 0 -> "${toDays()}d ${toHoursPart()}h"
    toHours() > 0 -> "${toHours()}h ${toMinutesPart()}m"
    toMinutes() > 0 -> "${toMinutes()}m"
    else -> "<1m"
}

internal val IndustryActivity.code: String
    get() = when (this) {
        IndustryActivity.Manufacturing -> "M"
        IndustryActivity.TimeEfficiency -> "TE"
        IndustryActivity.MaterialEfficiency -> "ME"
        IndustryActivity.Copying -> "C"
        IndustryActivity.Invention -> "I"
        IndustryActivity.ReverseEngineering -> "RE"
        IndustryActivity.Reaction -> "R"
    }
