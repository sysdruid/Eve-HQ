package eve.hq.core.production

/** What one run of a blueprint consumes and yields. */
data class Recipe(val product: TypeId, val perRun: Int, val materials: Map<TypeId, Int>)

fun interface RecipeBook {
    fun forProduct(product: TypeId): Recipe?
}
