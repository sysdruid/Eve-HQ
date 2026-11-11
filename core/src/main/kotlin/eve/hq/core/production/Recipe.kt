package eve.hq.core.production

/** What one run of [blueprint] consumes and yields, and how long it takes (blueprint as published, before any bonus). */
data class Recipe(
    val product: TypeId,
    val perRun: Int,
    val materials: Map<TypeId, Int>,
    val blueprint: TypeId,
    val secondsPerRun: Int,
)

fun interface RecipeBook {
    fun forProduct(product: TypeId): Recipe?
}
