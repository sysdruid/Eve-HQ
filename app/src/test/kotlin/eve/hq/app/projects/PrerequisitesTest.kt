package eve.hq.app.projects

import eve.hq.core.production.Contribution
import eve.hq.core.production.ContributionSource
import eve.hq.core.production.ItemCatalog
import eve.hq.core.production.ItemDemand
import eve.hq.core.production.StockpileStatus
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PrerequisitesTest {
    private val names = mapOf(1L to "Tritanium", 2L to "Pyerite", 3L to "Thulium", 4L to "Magnate", 5L to "Mystery")
    private val groups = mapOf(1L to "Mineral", 2L to "Mineral", 3L to "Moon Materials", 4L to "Frigate")
    private val catalog = object : ItemCatalog {
        override fun itemName(item: Long) = names[item]

        override fun groupName(item: Long) = groups[item]

        override fun findItem(name: String) = null
    }

    private fun demand(item: Long, required: Long, onHand: Long = 0, preferred: Long = required) =
        ItemDemand(item, listOf(Contribution(ContributionSource.ManualStockpile, required, preferred)), onHand)

    @Test
    fun `items are grouped by item group, groups by name with the unnamed ones last, items by name`() {
        val result = groupPrerequisites(
            listOf(demand(2, 10), demand(5, 7), demand(3, 4), demand(1, 20), demand(4, 1)),
            catalog,
        )

        assertThat(result.groups.map { it.name }).containsExactly("Frigate", "Mineral", "Moon Materials", "Other")
        assertThat(result.groups.single { it.name == "Mineral" }.lines.map { it.name })
            .containsExactly("Pyerite", "Tritanium")
    }

    @Test
    fun `a line carries the item and what is held against what is needed, for the level bar`() {
        val line = groupPrerequisites(listOf(demand(1, required = 2_778_000, onHand = 1_000)), catalog).groups.single().lines.single()

        assertThat(line).isEqualTo(PrerequisiteLine("Tritanium", item = 1, onHand = 1_000, required = 2_778_000, preferred = null))
    }

    @Test
    fun `a preferred level is carried only when it asks for more than is required`() {
        val lines = groupPrerequisites(listOf(demand(1, 100, preferred = 300), demand(2, 100)), catalog).groups.single { it.name == "Mineral" }.lines

        assertThat(lines.associate { it.name to it.preferred }).containsExactlyInAnyOrderEntriesOf(mapOf("Tritanium" to 300L, "Pyerite" to null))
    }

    @Test
    fun `each group has its own multibuy text in EVE's paste format, and there is one for everything`() {
        val result = groupPrerequisites(listOf(demand(2, 10), demand(3, 4), demand(1, 20, onHand = 5)), catalog)

        assertThat(result.groups.single { it.name == "Mineral" }.multibuy).isEqualTo("Pyerite 10\nTritanium 15")
        assertThat(result.groups.single { it.name == "Moon Materials" }.multibuy).isEqualTo("Thulium 4")
        assertThat(result.allMultibuy).isEqualTo("Pyerite 10\nThulium 4\nTritanium 15")
    }

    @Test
    fun `the multibuy asks for enough to reach the preferred level, not just the required one`() {
        val result = groupPrerequisites(listOf(demand(1, required = 100, onHand = 150, preferred = 300)), catalog)

        assertThat(result.groups.single().multibuy).isEqualTo("Tritanium 150") // 300 preferred less 150 held
    }

    @Test
    fun `an item without a known name is shown by its type id`() {
        val result = groupPrerequisites(listOf(demand(99, 3)), catalog)

        assertThat(result.groups.single().name).isEqualTo("Other")
        assertThat(result.groups.single().multibuy).isEqualTo("Type 99 3")
    }

    @Test
    fun `nothing missing gives no groups and no text`() {
        assertThat(groupPrerequisites(emptyList(), catalog)).isEqualTo(Prerequisites(emptyList(), ""))
    }
}
