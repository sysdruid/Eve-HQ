package eve.hq.core.production

import eve.hq.core.asset.AssetLocation

typealias TypeId = Long

/** Declared in the order a list should show them: the ones needing attention first. */
enum class StockpileStatus { BelowRequired, BelowPreferred, AboveOverflow, Ok }

/** A stockpile is one item, anywhere or at one station; [stationId] null means anywhere. */
data class StockpileKey(val item: TypeId, val stationId: Long? = null)

data class Stockpile(
    val item: TypeId,
    val name: String,
    val required: Long,
    val preferred: Long? = null,
    val overflow: Long? = null,
    val station: AssetLocation? = null,
) {
    val key get() = StockpileKey(item, station?.id)

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
