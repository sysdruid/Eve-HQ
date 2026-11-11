package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetSource
import eve.hq.core.attempt
import eve.hq.core.character.EveCharacter

interface StockpileRepository {
    fun all(): List<Stockpile>

    /** Adds the stockpile, or replaces the one for the same item. */
    fun save(stockpile: Stockpile)

    fun remove(item: TypeId)
}

data class StockpileRow(val stockpile: Stockpile, val onHand: Long, val status: StockpileStatus)

data class StockpileListing(val rows: List<StockpileRow>, val failures: List<AssetLoadFailure> = emptyList())

class ListStockpiles(private val stockpiles: StockpileRepository, private val assets: AssetSource) {
    suspend operator fun invoke(characters: List<EveCharacter>): StockpileListing {
        val results = characters.map { it to attempt { assets.load(it) } }
        val failures = results.mapNotNull { (character, result) ->
            result.exceptionOrNull()?.let { AssetLoadFailure(character.id, character.name, it.message ?: "Unable to load assets") }
        }
        // A failed character still counts from its last snapshot, so one outage does not make every stockpile look empty.
        val items = results.flatMap { (character, result) ->
            result.getOrElse { assets.cachedSnapshot(character.id)?.items.orEmpty() }
        }
        val onHand = items.groupBy { it.type.id }.mapValues { (_, owned) -> owned.sumOf { it.quantity.toLong() } }
        val rows = stockpiles.all().map { pile ->
            val quantity = onHand[pile.item] ?: 0
            StockpileRow(pile, quantity, pile.statusFor(quantity))
        }.sortedWith(compareBy({ it.status }, { it.stockpile.name.lowercase() }))
        return StockpileListing(rows, failures)
    }
}
