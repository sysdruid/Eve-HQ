package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ItemsTest {
    private val names = mapOf(1L to "Capital Construction Parts", 2L to "Fuel Block", 3L to "Rifter", 4L to "Tritanium")
    private val groups = mapOf(1L to "Composite", 2L to "Fuel Block", 3L to "Frigate")
    private val items = Items(object : ItemCatalog {
        override fun itemName(item: TypeId) = names[item]

        override fun groupName(item: TypeId) = groups[item]
    })

    @Test
    fun `group and item names match whatever the case`() {
        assertThat(items.isGroup(1, "composite")).isTrue()
        assertThat(items.isGroup(1, "Frigate")).isFalse()
        assertThat(items.isItem(2, "FUEL block")).isTrue()
        assertThat(items.isItem(2, "Rifter")).isFalse()
    }

    @Test
    fun `an unknown item or a missing group matches nothing`() {
        assertThat(items.isItem(99, "Rifter")).isFalse()
        assertThat(items.isGroup(4, "Composite")).isFalse()
        assertThat(items.isItem(4, "Tritanium")).isTrue()
    }

    @Test
    fun `a project can combine the questions with plain boolean operators`() {
        val recipes = mapOf(
            3L to Recipe(3, 1, mapOf(1L to 5, 2L to 4, 4L to 100)),
            1L to Recipe(1, 1, mapOf(4L to 10)),
            2L to Recipe(2, 1, mapOf(4L to 7)),
        )
        // Builds composites and the hull itself, but buys fuel blocks: "not an item" is just `!`.
        val project = FixedRequirementsProject("Frigates", listOf(Requirement(3, 2, "test"))) {
            (items.isGroup(it, "Composite") || items.isGroup(it, "Frigate")) && !items.isItem(it, "Fuel Block")
        }

        val plan = PlanProduction({ recipes[it] })(listOf(project))

        assertThat(plan.jobs.map { it.product }).containsExactlyInAnyOrder(3L, 1L)
        assertThat(plan.shoppingList.associate { it.item to it.deficit })
            .containsExactlyInAnyOrderEntriesOf(mapOf(2L to 8L, 4L to 300L))
    }
}
