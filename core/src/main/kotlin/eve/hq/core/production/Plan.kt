package eve.hq.core.production

import kotlin.math.max

/** Who asked for some of an item. */
sealed interface ContributionSource {
    data object ManualStockpile : ContributionSource

    data class ProjectGoal(val project: String, val because: String) : ContributionSource

    /** The jobs that make [product] consume this item. */
    data class Jobs(val product: TypeId) : ContributionSource
}

data class Contribution(val source: ContributionSource, val required: Long, val preferred: Long = required)

/** Everything demanded of one item, against what is on hand. */
data class ItemDemand(val item: TypeId, val contributions: List<Contribution>, val onHand: Long) {
    val required: Long get() = contributions.sumOf(Contribution::required)
    val preferred: Long get() = contributions.sumOf(Contribution::preferred)
    val deficit: Long get() = max(0, required - onHand)
    val deficitToPreferred: Long get() = max(0, preferred - onHand)
    val status: StockpileStatus
        get() = when {
            onHand < required -> StockpileStatus.BelowRequired
            onHand < preferred -> StockpileStatus.BelowPreferred
            else -> StockpileStatus.Ok
        }
}

/** [projects] are the projects that expand [product]'s inputs. */
data class PlannedJob(val product: TypeId, val runs: Long, val projects: Set<String>)

data class Plan(val demands: List<ItemDemand>, val jobs: List<PlannedJob>) {
    /** Items no job produces and whose stock is below the level the owner wants: what to buy or otherwise obtain. */
    val shoppingList: List<ItemDemand>
        get() {
            val built = jobs.mapTo(mutableSetOf(), PlannedJob::product)
            return demands.filter { it.item !in built && it.deficitToPreferred > 0 }
        }
}
