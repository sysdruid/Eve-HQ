package eve.hq.core.production

import eve.hq.core.production.ContributionSource.Jobs
import eve.hq.core.production.ContributionSource.ManualStockpile
import eve.hq.core.production.ContributionSource.ProjectGoal

/**
 * Plans the jobs and purchases that bring pooled stock up to the demand of all [projects] and [manual] stockpiles.
 *
 * Every demand on an item is added up first and stock is subtracted once, so two projects cannot claim the same
 * stock. An item is built when some project reaches it without stopping at it; a project that stops there still adds
 * its demand to the total the jobs are sized to. A manual stockpile only adds demand and never builds anything.
 */
class PlanProduction(private val recipes: RecipeBook) {
    operator fun invoke(
        projects: List<Project>,
        manual: List<Stockpile> = emptyList(),
        stock: Map<TypeId, Long> = emptyMap(),
    ): Plan {
        val contributions = linkedMapOf<TypeId, MutableList<Contribution>>()
        val reachedBy = mutableMapOf<TypeId, MutableSet<Project>>()
        fun add(item: TypeId, source: ContributionSource, required: Long, preferred: Long = required) {
            if (required > 0) contributions.getOrPut(item) { mutableListOf() } += Contribution(source, required, preferred)
        }
        projects.forEach { project ->
            project.requirements().forEach { requirement ->
                add(requirement.item, ProjectGoal(project.name, requirement.because), requirement.required, requirement.preferred ?: requirement.required)
                reachedBy.getOrPut(requirement.item) { linkedSetOf() } += project
            }
        }
        manual.forEach { add(it.item, ManualStockpile, it.required, it.preferred ?: it.required) }

        val demands = mutableListOf<ItemDemand>()
        val jobs = mutableListOf<PlannedJob>()
        // Parents come before their materials, so every demand on an item has arrived by the time it is sized.
        for (item in parentsFirst(contributions.keys, projects)) {
            val asked = contributions[item] ?: continue
            val demand = ItemDemand(item, asked.toList(), stock[item] ?: 0)
            demands += demand
            val expanders = reachedBy[item].orEmpty().filter { item !in it.stopItems }
            val recipe = recipes.forProduct(item)
            if (recipe == null || expanders.isEmpty() || demand.deficit == 0L) continue
            val runs = (demand.deficit + recipe.perRun - 1) / recipe.perRun
            jobs += PlannedJob(item, runs, expanders.mapTo(linkedSetOf(), Project::name))
            recipe.materials.forEach { (material, perRun) ->
                add(material, Jobs(item), perRun * runs)
                reachedBy.getOrPut(material) { linkedSetOf() } += expanders
            }
        }
        return Plan(demands, jobs)
    }

    /** Whether any project could expand [item]; used only to order the walk, so it may over-approximate. */
    private fun expandable(item: TypeId, projects: List<Project>) =
        recipes.forProduct(item) != null && !(projects.isNotEmpty() && projects.all { item in it.stopItems })

    private fun parentsFirst(roots: Collection<TypeId>, projects: List<Project>): List<TypeId> {
        val finished = mutableListOf<TypeId>()
        val visiting = mutableSetOf<TypeId>()
        val done = mutableSetOf<TypeId>()
        fun visit(item: TypeId) {
            if (item in done) return
            check(visiting.add(item)) { "Recipes form a cycle through type $item" }
            if (expandable(item, projects)) recipes.forProduct(item)?.materials?.keys?.forEach(::visit)
            visiting.remove(item)
            done += item
            finished += item
        }
        roots.forEach(::visit)
        return finished.asReversed()
    }
}
