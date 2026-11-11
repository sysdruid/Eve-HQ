package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity

/** What one run of [blueprint] consumes and yields, and how long it takes (blueprint as published, before any bonus). */
data class Recipe(
    val product: TypeId,
    val perRun: Int,
    val materials: Map<TypeId, Int>,
    val blueprint: TypeId,
    val secondsPerRun: Int,
    /** Manufacturing or reaction: they use different skills and structure bonuses. */
    val activity: IndustryActivity = IndustryActivity.Manufacturing,
)

fun interface RecipeBook {
    fun forProduct(product: TypeId): Recipe?
}
