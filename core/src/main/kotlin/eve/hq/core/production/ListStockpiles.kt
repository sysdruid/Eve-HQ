package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.CharacterAssets
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
        val results = assets.assetsOf(characters, refresh)
        val onHand = results.flatMap { it.snapshot?.items.orEmpty() }
            .groupBy { it.type.id }
            .mapValues { (_, owned) -> owned.sumOf { it.quantity.toLong() } }
        val rows = stockpiles.all().map { pile ->
            val quantity = onHand[pile.item] ?: 0
            StockpileRow(pile, quantity, pile.statusFor(quantity))
        }.sortedWith(compareBy({ it.status }, { it.stockpile.name.lowercase() }))
        return StockpileListing(rows, results.mapNotNull(CharacterAssets::failure))
    }
}
