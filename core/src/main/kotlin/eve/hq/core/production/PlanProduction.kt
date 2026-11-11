package eve.hq.core.production

import eve.hq.core.production.ContributionSource.Jobs
import eve.hq.core.production.ContributionSource.ManualStockpile
import eve.hq.core.production.ContributionSource.ProjectGoal

/**
 * Plans the jobs and purchases that bring pooled stock up to the demand of all [projects] and [manual] stockpiles.
 *
 * Every demand on an item is added up first and stock is subtracted once, so two projects cannot claim the same
 * stock. An item is built when some project reaches it and builds it; a project that buys it still adds
 * its demand to the total the jobs are sized to. A manual stockpile only adds demand and never builds anything.
 */
class PlanProduction(private val recipes: RecipeBook, private val runTimes: RunTimes? = null) {
    operator fun invoke(
        projects: List<Project>,
        manual: List<Stockpile> = emptyList(),
        stock: Map<TypeId, Long> = emptyMap(),
    ): Plan {
        val actual = expand(projects, manual, stock)
        // Phases come from the structure of the recipe tree, not from what is left to do: planning once more without stock
        // shows every layer, so a phase keeps its number (and its tab) however much stock covers it.
        val structure = if (stock.isEmpty()) actual else expand(projects, manual, emptyMap())
        val depthOf = structure.jobs.associate { it.product to it.phase }
        val deepest = depthOf.values.maxOrNull() ?: 0
        // Depth counts from the goals, but phases read upstream first.
        fun number(depth: Int) = deepest - depth + 1
        val layers = depthOf.mapValues { (_, depth) -> number(depth) }
        val jobs = actual.jobs.map { it.copy(phase = number(depthOf[it.product] ?: it.phase)) }
        return Plan(actual.demands, jobs, layers)
    }

    /** Demands and jobs, each job's phase being its raw depth from the goals. */
    private class Expansion(val demands: List<ItemDemand>, val jobs: List<PlannedJob>)

    private fun expand(projects: List<Project>, manual: List<Stockpile>, stock: Map<TypeId, Long>): Expansion {
        val contributions = linkedMapOf<TypeId, MutableList<Contribution>>()
        val reachedBy = mutableMapOf<TypeId, MutableSet<Project>>()
        // The deepest layer an item is needed at, so its job comes after every job that consumes it.
        val depth = mutableMapOf<TypeId, Int>()
        fun add(item: TypeId, source: ContributionSource, required: Long, preferred: Long = required) {
            if (required > 0) contributions.getOrPut(item) { mutableListOf() } += Contribution(source, required, preferred)
        }
        projects.forEach { project ->
            project.requirements().forEach { requirement ->
                add(requirement.item, ProjectGoal(project.name, requirement.because), requirement.required, requirement.preferred ?: requirement.required)
                reachedBy.getOrPut(requirement.item) { linkedSetOf() } += project
                depth.merge(requirement.item, 1, ::maxOf)
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
            val expanders = reachedBy[item].orEmpty().filter { it.builds(item) }
            val recipe = recipes.forProduct(item)
            if (recipe == null || expanders.isEmpty() || demand.deficit == 0L) continue
            val runs = (demand.deficit + recipe.perRun - 1) / recipe.perRun
            val phase = depth.getValue(item)
            val cycle = expanders.mapNotNull(Project::cycle).minByOrNull { it.length }
            val projectNames = expanders.mapTo(linkedSetOf(), Project::name)
            val modifiers = runTimes?.modifiers(recipe) ?: TimeModifiers()
            // A job may not run longer than the usable cycle, so it is split into jobs that each fit, the remainder last.
            val perJob = cycle?.let { modifiers.maxRuns(recipe.secondsPerRun, it.usable) } ?: runs
            val seconds = modifiers.seconds(recipe.secondsPerRun, 1).toInt()
            for (chunk in generateSequence(runs) { it - perJob }.takeWhile { it > 0 }.map { minOf(it, perJob) }) {
                jobs += PlannedJob(item, chunk, projectNames, phase, recipe.blueprint, seconds, modifiers.seconds(recipe.secondsPerRun, chunk))
            }
            recipe.materials.forEach { (material, perRun) ->
                add(material, Jobs(item), perRun * runs)
                reachedBy.getOrPut(material) { linkedSetOf() } += expanders
                depth.merge(material, phase + 1, ::maxOf)
            }
        }
        return Expansion(demands, jobs)
    }

    /** Whether any project builds [item]; used only to order the walk, so it may include items nobody reaches. */
    private fun expandable(item: TypeId, projects: List<Project>) =
        recipes.forProduct(item) != null && projects.any { it.builds(item) }

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
