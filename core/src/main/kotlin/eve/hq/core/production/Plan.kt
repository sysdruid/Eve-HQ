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

/**
 * [projects] are the projects that build [product]. [phase] is its layer, counted from 1 at the most upstream one (the
 * jobs furthest from the goals, such as reactions) up to the last, whose jobs make the goals' own inputs. Phases are not an
 * order to run in. All of them run in parallel within the cycle, each making the input the next phase consumes.
 */
data class PlannedJob(
    val product: TypeId,
    val runs: Long,
    val projects: Set<String>,
    val phase: Int,
    val blueprint: TypeId,
    val secondsPerRun: Int,
    /** The job's exact length; [secondsPerRun] is rounded, so runs x it can overstate a job of many short runs. */
    val totalSeconds: Long = runs * secondsPerRun,
)

/** A layer of the recipe tree: [products] are everything built in it, whether or not the plan still asks for a job. */
data class PlanPhase(val number: Int, val jobs: List<PlannedJob>, val products: Set<TypeId> = emptySet())

data class Plan(
    val demands: List<ItemDemand>,
    val jobs: List<PlannedJob>,
    /** The phase of every item that is built, independent of stock, so phase numbers stay put as stock changes. */
    val layers: Map<TypeId, Int> = emptyMap(),
) {
    val phases: List<PlanPhase>
        get() = layers.entries.groupBy({ it.value }, { it.key }).toSortedMap().map { (number, products) ->
            PlanPhase(number, jobs.filter { it.phase == number }, products.toSet())
        }

    /**
     * Items no job of the plan produces and whose stock is below the level the owner wants. Whether each is bought, mined or
     * built elsewhere is the owner's call, hence "prerequisites", not a shopping list.
     */
    val prerequisites: List<ItemDemand>
        get() {
            val built = jobs.mapTo(mutableSetOf(), PlannedJob::product)
            return demands.filter { it.item !in built && it.deficitToPreferred > 0 }
        }
}
