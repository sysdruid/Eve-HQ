package eve.hq.app.projects

import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.ItemDemand
import eve.hq.core.production.StockpileStatus
import java.util.Locale

data class PrerequisiteLine(val text: String, val status: StockpileStatus, val item: Long)

/** One item group of the prerequisites, with the EVE multibuy paste text for just that group. */
data class PrerequisiteGroup(val name: String, val lines: List<PrerequisiteLine>, val multibuy: String)

data class Prerequisites(val groups: List<PrerequisiteGroup>, val allMultibuy: String)

private const val OTHER = "Other"

/**
 * Groups the prerequisites by item group (an item the data does not know goes to "Other", last), and builds the multibuy paste
 * text, which asks for what it takes to reach the preferred level, since that is where the owner can relax.
 */
internal fun groupPrerequisites(demands: List<ItemDemand>, catalog: ItemCatalog): Prerequisites {
    fun name(demand: ItemDemand) = catalog.itemName(demand.item) ?: "Type ${demand.item}"

    fun multibuy(items: List<ItemDemand>) = items.filter { it.deficitToPreferred > 0 }
        .joinToString("\n") { "${name(it)} ${it.deficitToPreferred}" }

    fun line(demand: ItemDemand) = PrerequisiteLine(
        "${name(demand)}  need ${count(demand.required)}  on hand ${count(demand.onHand)}  short ${count(demand.deficit)}",
        demand.status,
        demand.item,
    )

    fun byName(items: List<ItemDemand>) = items.sortedBy { name(it).lowercase() }

    val groups = demands.groupBy { catalog.groupName(it.item) ?: OTHER }.entries
        .sortedWith(compareBy({ it.key == OTHER }, { it.key.lowercase() }))
        .map { (group, items) ->
            val sorted = byName(items)
            PrerequisiteGroup(group, sorted.map(::line), multibuy(sorted))
        }
    return Prerequisites(groups, multibuy(byName(demands)))
}

private fun count(value: Long) = "%,d".format(Locale.ROOT, value)
