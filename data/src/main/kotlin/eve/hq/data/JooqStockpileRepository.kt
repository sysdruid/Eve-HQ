package eve.hq.data

import eve.hq.core.production.Stockpile
import eve.hq.core.production.StockpileRepository
import eve.hq.core.production.TypeId
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
        Stockpile(record.typeId, record.name, record.required, record.preferred, record.overflow)
    }

    override fun save(stockpile: Stockpile) {
        val columns = mapOf<Field<*>, Any?>(
            STOCKPILES.NAME to stockpile.name,
            STOCKPILES.REQUIRED to stockpile.required,
            STOCKPILES.PREFERRED to stockpile.preferred,
            STOCKPILES.OVERFLOW to stockpile.overflow,
        )
        dsl.insertInto(STOCKPILES)
            .set(STOCKPILES.TYPE_ID, stockpile.item)
            .set(columns)
            .onConflict(STOCKPILES.TYPE_ID)
            .doUpdate()
            .set(columns)
            .execute()
    }

    override fun remove(item: TypeId) {
        dsl.deleteFrom(STOCKPILES).where(STOCKPILES.TYPE_ID.eq(item)).execute()
    }
}
