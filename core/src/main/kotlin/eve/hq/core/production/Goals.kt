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
    // A single run longer than the usable cycle still needs its inputs, so at least one run counts.
    val largest = largestNeeds(recipes, hulls) { recipe -> maxOf(1L, cycle.usable.seconds / recipe.secondsPerRun) }
    // Decimal arithmetic: 15 x 1.4 must be exactly 21, not 21.000000000000004 rounded up to 22.
    val scale = BigDecimal(cycle.speed.toString()) * BigDecimal(jobs)
    return largest.map { (material, quantity) ->
        Requirement(material, (BigDecimal(quantity) * scale).setScale(0, RoundingMode.CEILING).toLong(), because)
    }
}

/**
 * What must be on hand to run exactly [runs] runs of any one of [hulls]: per material the largest need of any single hull,
 * as for readiness, but with a fixed run count (invented copies have a fixed number of runs) and no speed, since the count
 * of hulls is exact.
 */
fun fixedRunRequirements(recipes: RecipeBook, hulls: List<TypeId>, runs: Long, because: String): List<Requirement> =
    largestNeeds(recipes, hulls) { runs }.map { (material, quantity) -> Requirement(material, quantity, because) }

private fun largestNeeds(recipes: RecipeBook, hulls: List<TypeId>, runsOf: (Recipe) -> Long): Map<TypeId, Long> {
    val perHull = hulls.map { hull ->
        val recipe = requireNotNull(recipes.forProduct(hull)) { "Type $hull has no recipe to take inputs from" }
        val runs = runsOf(recipe)
        recipe.materials.mapValues { (_, perRun) -> perRun * runs }
    }
    return perHull.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, needs) -> needs.max() }
}
