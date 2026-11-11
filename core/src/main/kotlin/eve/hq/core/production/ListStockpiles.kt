package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.character.EveCharacter
import java.time.Clock

interface StockpileRepository {
    fun all(): List<Stockpile>

    /** Adds the stockpile, or replaces the one for the same item. */
    fun save(stockpile: Stockpile)

    fun remove(item: TypeId)
}

data class StockpileRow(val stockpile: Stockpile, val onHand: Long, val status: StockpileStatus)

data class StockpileListing(val rows: List<StockpileRow>, val failures: List<AssetLoadFailure> = emptyList())

class ListStockpiles(private val stockpiles: StockpileRepository, private val assets: AssetService) {
    constructor(stockpiles: StockpileRepository, source: AssetSource, clock: Clock = Clock.systemUTC()) :
        this(stockpiles, AssetService(source, clock))

    suspend operator fun invoke(characters: List<EveCharacter>, refresh: Boolean = false): StockpileListing {
        val owned = assets.assets(characters, refresh = refresh)
        val onHand = owned.quantities()
        val rows = stockpiles.all().map { pile ->
            val quantity = onHand[pile.item] ?: 0
            StockpileRow(pile, quantity, pile.statusFor(quantity))
        }.sortedWith(compareBy({ it.status }, { it.stockpile.name.lowercase() }))
        return StockpileListing(rows, owned.failures)
    }
}
