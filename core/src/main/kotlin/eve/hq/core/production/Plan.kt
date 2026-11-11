package eve.hq.core.production

import kotlin.math.max

/** Who asked for some of an item. */
sealed interface ContributionSource {
    data object ManualStockpile : ContributionSource

    data class ProjectGoal(val project: String, val because: String) : ContributionSource

    /** The jobs that make [product] consume this item. */
    data class Jobs(val product: TypeId) : ContributionSource
}

data class Contribution(val source: ContributionSource, val quantity: Long)

/** Everything demanded of one item, against what is on hand. */
data class ItemDemand(val item: TypeId, val contributions: List<Contribution>, val onHand: Long) {
    val required: Long get() = contributions.sumOf(Contribution::quantity)
    val deficit: Long get() = max(0, required - onHand)
}

/** [projects] are the projects that expand [product]'s inputs. */
data class PlannedJob(val product: TypeId, val runs: Long, val projects: Set<String>)

data class Plan(val demands: List<ItemDemand>, val jobs: List<PlannedJob>) {
    /** Items no job produces and that stock does not cover: what has to be bought or otherwise obtained. */
    val shoppingList: List<ItemDemand>
        get() {
            val built = jobs.mapTo(mutableSetOf(), PlannedJob::product)
            return demands.filter { it.item !in built && it.deficit > 0 }
        }
}
