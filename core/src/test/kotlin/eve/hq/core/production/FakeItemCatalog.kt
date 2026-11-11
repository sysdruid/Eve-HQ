package eve.hq.core.production

class FakeItemCatalog(
    private val names: Map<TypeId, String> = emptyMap(),
    private val groups: Map<TypeId, String> = emptyMap(),
) : ItemCatalog {
    override fun itemName(item: TypeId) = names[item]

    override fun groupName(item: TypeId) = groups[item]

    override fun findItem(name: String) = names.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
}
