package eve.hq.data

import eve.hq.core.asset.AssetLocation
import eve.hq.core.production.Stockpile
import eve.hq.core.production.StockpileRepository
import eve.hq.core.production.StockpileKey
import eve.hq.data.jooq.tables.Stockpiles.STOCKPILES
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

class JooqStockpileRepository(dataSource: DataSource) : StockpileRepository {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun all(): List<Stockpile> = dsl.selectFrom(STOCKPILES).fetch { record ->
        val station = record.stationId.takeIf { it != ANYWHERE }?.let { AssetLocation(it, record.stationName) }
        Stockpile(record.typeId, record.name, record.required, record.preferred, record.overflow, station)
    }

    override fun save(stockpile: Stockpile) {
        val columns = mapOf<Field<*>, Any?>(
            STOCKPILES.NAME to stockpile.name,
            STOCKPILES.REQUIRED to stockpile.required,
            STOCKPILES.PREFERRED to stockpile.preferred,
            STOCKPILES.OVERFLOW to stockpile.overflow,
            STOCKPILES.STATION_NAME to stockpile.station?.name,
        )
        dsl.insertInto(STOCKPILES)
            .set(STOCKPILES.TYPE_ID, stockpile.item)
            .set(STOCKPILES.STATION_ID, stockpile.station?.id ?: ANYWHERE)
            .set(columns)
            .onConflict(STOCKPILES.TYPE_ID, STOCKPILES.STATION_ID)
            .doUpdate()
            .set(columns)
            .execute()
    }

    override fun remove(key: StockpileKey) {
        dsl.deleteFrom(STOCKPILES)
            .where(STOCKPILES.TYPE_ID.eq(key.item))
            .and(STOCKPILES.STATION_ID.eq(key.stationId ?: ANYWHERE))
            .execute()
    }

    private companion object {
        /** The stored stand-in for "no station", since a primary key cannot hold null. */
        const val ANYWHERE = 0L
    }
}
