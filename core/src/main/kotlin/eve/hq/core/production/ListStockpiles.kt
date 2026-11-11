package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.character.EveCharacter
import java.time.Clock

interface StockpileRepository {
    fun all(): List<Stockpile>

    /** Adds the stockpile, or replaces the one with the same key. */
    fun save(stockpile: Stockpile)

    fun remove(key: StockpileKey)
}

data class StockpileRow(val stockpile: Stockpile, val onHand: Long, val status: StockpileStatus)

data class StockpileListing(
    val rows: List<StockpileRow>,
    val failures: List<AssetLoadFailure> = emptyList(),
    /** Where the assets are, for choosing a stockpile's station. */
    val stations: List<AssetLocation> = emptyList(),
)

class ListStockpiles(private val stockpiles: StockpileRepository, private val assets: AssetService) {
    constructor(stockpiles: StockpileRepository, source: AssetSource, clock: Clock = Clock.systemUTC()) :
        this(stockpiles, AssetService(source, clock))

    suspend operator fun invoke(characters: List<EveCharacter>, refresh: Boolean = false): StockpileListing {
        val owned = assets.assets(characters, refresh = refresh)
        val rows = stockpiles.all().map { pile ->
            val quantity = owned.quantityOf(pile.item, pile.station?.id)
            StockpileRow(pile, quantity, pile.statusFor(quantity))
        }.sortedWith(compareBy({ it.status }, { it.stockpile.name.lowercase() }))
        return StockpileListing(rows, owned.failures, owned.stations())
    }
}
