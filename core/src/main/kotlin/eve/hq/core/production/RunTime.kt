package eve.hq.core.production

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Everything that shortens a job. Each reduction multiplies the time, they do not add up: a 20% blueprint and a 15%
 * structure make 0.8 x 0.85 = 0.68, not 0.65. [blueprintTe] is the blueprint's time efficiency in percent (0 to 20);
 * [skills], [implants] and [structure] are fractions (0.15 is 15% off).
 */
data class TimeModifiers(
    val blueprintTe: Int = 0,
    val skills: List<Double> = emptyList(),
    val implants: List<Double> = emptyList(),
    val structure: Double = 0.0,
) {
    init {
        require(blueprintTe in 0..20) { "Blueprint TE must be between 0 and 20 percent" }
        require((skills + implants + structure).all { it >= 0.0 && it < 1.0 }) { "A reduction must be between 0 and 100 percent" }
    }

    /** The multiplier on base time, exactly. */
    val factor: BigDecimal
        get() = (listOf(blueprintTe / 100.0) + skills + implants + structure)
            .fold(BigDecimal.ONE) { product, reduction -> product * (BigDecimal.ONE - BigDecimal(reduction.toString())) }

    /** How long [runs] runs of a blueprint with [baseSecondsPerRun] take, rounded once at the end, not per run. */
    fun seconds(baseSecondsPerRun: Int, runs: Long): Long =
        (BigDecimal(baseSecondsPerRun) * BigDecimal(runs) * factor).setScale(0, RoundingMode.HALF_UP).toLong()
}

object ManufacturingSkills {
    private val INDUSTRY_PER_LEVEL = BigDecimal("0.04")
    private val ADVANCED_INDUSTRY_PER_LEVEL = BigDecimal("0.03")

    /** The time reductions of Industry (4% a level) and Advanced Industry (3% a level). */
    fun reductions(industry: Int, advancedIndustry: Int): List<Double> {
        require(industry in 0..5 && advancedIndustry in 0..5) { "Skill levels are 0 to 5" }
        return listOf(
            (INDUSTRY_PER_LEVEL * BigDecimal(industry)).toDouble(),
            (ADVANCED_INDUSTRY_PER_LEVEL * BigDecimal(advancedIndustry)).toDouble(),
        )
    }
}
