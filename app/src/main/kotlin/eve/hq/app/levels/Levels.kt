package eve.hq.app.levels

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** How well on-hand stock covers what is needed. */
enum class Level { Covered, Close, Low, Short }

// Whole-number arithmetic: 85% of 2,778,000 must be exactly 2,361,300, not a float's neighbour of it.
fun levelOf(onHand: Long, required: Long): Level = when {
    onHand >= required -> Level.Covered
    onHand * 100 >= required * 85 -> Level.Close
    onHand * 2 >= required -> Level.Low
    else -> Level.Short
}

/** A short form for large numbers, to three significant digits: 2,778,000 is "2.78M". Under a thousand is exact. */
fun compact(value: Long): String {
    if (value < 1_000) return value.toString()
    // Rounded first, so 999,999 becomes 1M and not "1000k".
    val rounded = BigDecimal(value).round(MathContext(3, RoundingMode.HALF_UP))
    val (shift, unit) = when {
        rounded >= BigDecimal(1_000_000_000) -> 9 to "B"
        rounded >= BigDecimal(1_000_000) -> 6 to "M"
        else -> 3 to "k"
    }
    return rounded.movePointLeft(shift).stripTrailingZeros().toPlainString() + unit
}
