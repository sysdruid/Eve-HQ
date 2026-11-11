package eve.hq.core.production

class FakeItemCatalog(
    private val names: Map<TypeId, String> = emptyMap(),
    private val groups: Map<TypeId, String> = emptyMap(),
) : ItemCatalog {
    override fun itemName(item: TypeId) = names[item]

    override fun groupName(item: TypeId) = groups[item]

    override fun findItem(name: String) = names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
}

/** Combines catalogs; the first one that knows an item answers. */
operator fun ItemCatalog.plus(other: ItemCatalog): ItemCatalog = object : ItemCatalog {
    override fun itemName(item: TypeId) = this@plus.itemName(item) ?: other.itemName(item)

    override fun groupName(item: TypeId) = this@plus.groupName(item) ?: other.groupName(item)

    override fun findItem(name: String) = this@plus.findItem(name) ?: other.findItem(name)
}
