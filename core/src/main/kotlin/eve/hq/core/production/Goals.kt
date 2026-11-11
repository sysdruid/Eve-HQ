package eve.hq.core.production

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The requirements of running [jobs] jobs of [product]: every direct input of its recipe, times [jobs]. A readiness
 * goal is built on this, and so is any project that must have "everything needed to start N jobs" on hand.
 */
fun inputsOfJobs(recipes: RecipeBook, product: TypeId, jobs: Long, because: String): List<Requirement> {
    val recipe = requireNotNull(recipes.forProduct(product)) { "Type $product has no recipe to take inputs from" }
    return recipe.materials.map { (material, perRun) -> Requirement(material, perRun * jobs, because) }
}

/**
 * What must be on hand to start [jobs] jobs of any of [hulls], each job running a whole cycle. Per hull, the runs that
 * fit in the cycle at base time times its recipe; per material the largest of those over all hulls (not their sum,
 * since only one kind is built per job; not their average, which understates the most demanding hull), then times the
 * cycle's speed and [jobs], rounded up. Only direct inputs are asked for; the layers below follow from the plan.
 */
fun readinessRequirements(
    recipes: RecipeBook,
    hulls: List<TypeId>,
    cycle: CycleSettings,
    jobs: Long,
    because: String,
): List<Requirement> {
    val perHull = hulls.map { hull ->
        val recipe = requireNotNull(recipes.forProduct(hull)) { "Type $hull has no recipe to take inputs from" }
        // A single run longer than the cycle still needs its inputs, so at least one run counts.
        val runs = maxOf(1L, cycle.length.seconds / recipe.secondsPerRun)
        recipe.materials.mapValues { (_, perRun) -> perRun * runs }
    }
    val largest = perHull.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, needs) -> needs.max() }
    // Decimal arithmetic: 15 x 1.4 must be exactly 21, not 21.000000000000004 rounded up to 22.
    val scale = BigDecimal(cycle.speed.toString()) * BigDecimal(jobs)
    return largest.map { (material, quantity) ->
        Requirement(material, (BigDecimal(quantity) * scale).setScale(0, RoundingMode.CEILING).toLong(), because)
    }
}
