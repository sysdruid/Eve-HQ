package eve.hq.core.production

data class ItemType(val id: TypeId, val name: String)

fun interface TypeCatalog {
    /** Published types whose name contains [query], shortest names first; empty when the query is blank. */
    fun search(query: String): List<ItemType>
}
