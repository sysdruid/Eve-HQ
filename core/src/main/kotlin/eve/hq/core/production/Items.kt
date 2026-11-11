package eve.hq.core.production

/** What a project definition may ask about an item. */
interface ItemCatalog {
    fun itemName(item: TypeId): String?

    fun groupName(item: TypeId): String?

    /** The item with exactly this name, ignoring case; null when there is none. */
    fun findItem(name: String): TypeId?
}

/** Plain boolean questions about items, for writing a project's build rule with `&&`, `||` and `!`. */
class Items(private val catalog: ItemCatalog) {
    fun isGroup(item: TypeId, groupName: String): Boolean = catalog.groupName(item).equals(groupName, ignoreCase = true)

    fun idOf(name: String): TypeId? = catalog.findItem(name)

    /** For project definitions, where a misspelt item name should fail at once, not plan silently without it. */
    fun id(name: String): TypeId = requireNotNull(idOf(name)) { "Unknown item \"$name\"" }

    fun isItem(item: TypeId, name: String): Boolean = catalog.itemName(item).equals(name, ignoreCase = true)
}
