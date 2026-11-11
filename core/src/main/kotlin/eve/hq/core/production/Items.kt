package eve.hq.core.production

/** What a project definition may ask about an item. */
interface ItemCatalog {
    fun itemName(item: TypeId): String?

    fun groupName(item: TypeId): String?
}

/** Plain boolean questions about items, for writing a project's build rule with `&&`, `||` and `!`. */
class Items(private val catalog: ItemCatalog) {
    fun isGroup(item: TypeId, groupName: String): Boolean = catalog.groupName(item).equals(groupName, ignoreCase = true)

    fun isItem(item: TypeId, name: String): Boolean = catalog.itemName(item).equals(name, ignoreCase = true)
}
