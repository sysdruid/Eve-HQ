package eve.hq.app.jobs

import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.JobRow
import eve.hq.core.industry.JobStatus
import java.time.Duration
import java.time.Instant

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

enum class JobLineStatus(val label: String) { Running("Active"), Paused("Paused"), Ready("Ready") }

data class JobLine(val text: String, val status: JobLineStatus, val progress: Float, val productId: Long? = null, val characterId: Long? = null)

/** One running job as a line with its progress, as the Jobs tab and the project tabs show it. */
internal fun JobRow.toLine(now: Instant): JobLine {
    val ready = isReady(now)
    val etl = if (ready) "done" else remaining(now).asEtl()
    val status = when {
        ready -> JobLineStatus.Ready
        this.status == JobStatus.Paused -> JobLineStatus.Paused
        else -> JobLineStatus.Running
    }
    return JobLine(
        text = "$characterName-$itemName [$runs] ${activity.code}  ETL: $etl  ${status.label} @ $facilityName",
        status = status,
        progress = progress(now).toFloat(),
        productId = productId,
        characterId = characterId,
    )
}
