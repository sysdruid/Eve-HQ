package eve.hq.core.production

typealias TypeId = Long

/** Declared in the order a list should show them: the ones needing attention first. */
enum class StockpileStatus { BelowRequired, BelowPreferred, AboveOverflow, Ok }

data class Stockpile(
    val item: TypeId,
    val name: String,
    val required: Long,
    val preferred: Long? = null,
    val overflow: Long? = null,
) {
    init {
        require(required >= 0) { "Required level must not be negative" }
        val levels = listOfNotNull(required, preferred, overflow)
        require(levels == levels.sorted()) { "Levels must satisfy required <= preferred <= overflow" }
    }

    fun statusFor(onHand: Long): StockpileStatus = when {
        onHand < required -> StockpileStatus.BelowRequired
        preferred != null && onHand < preferred -> StockpileStatus.BelowPreferred
        overflow != null && onHand > overflow -> StockpileStatus.AboveOverflow
        else -> StockpileStatus.Ok
    }
}
