package eve.hq.core.production

/**
 * The requirements of running [jobs] jobs of [product]: every direct input of its recipe, times [jobs]. A readiness
 * goal is built on this, and so is any project that must have "everything needed to start N jobs" on hand.
 */
fun inputsOfJobs(recipes: RecipeBook, product: TypeId, jobs: Long, because: String): List<Requirement> {
    val recipe = requireNotNull(recipes.forProduct(product)) { "Type $product has no recipe to take inputs from" }
    return recipe.materials.map { (material, perRun) -> Requirement(material, perRun * jobs, because) }
}
