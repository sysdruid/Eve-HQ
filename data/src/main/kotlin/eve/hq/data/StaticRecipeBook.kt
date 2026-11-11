package eve.hq.data

import eve.hq.core.production.Recipe
import eve.hq.core.production.RecipeBook
import eve.hq.core.production.TypeId
import eve.hq.data.jooq.tables.BlueprintActivities.BLUEPRINT_ACTIVITIES
import eve.hq.data.jooq.tables.BlueprintMaterials.BLUEPRINT_MATERIALS
import eve.hq.data.jooq.tables.BlueprintProducts.BLUEPRINT_PRODUCTS
import eve.hq.data.jooq.tables.Types.TYPES
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.RenderQuotedNames
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import javax.sql.DataSource

/**
 * Recipes from the imported SDE. Only published blueprints count: the archive also holds development blueprints (a
 * "Test Reaction Blueprint" makes Tungsten Carbide too), and nobody can build from those. If a product still has
 * several published blueprints the choice is not guessed.
 */
class StaticRecipeBook(dataSource: DataSource) : RecipeBook {
    private val dsl: DSLContext = DSL.using(
        dataSource,
        SQLDialect.H2,
        Settings().withRenderQuotedNames(RenderQuotedNames.NEVER),
    )

    override fun forProduct(product: TypeId): Recipe? {
        val blueprintType = TYPES.`as`("blueprint_type")
        val makers = dsl.select(
            BLUEPRINT_PRODUCTS.BLUEPRINT_TYPE_ID,
            BLUEPRINT_PRODUCTS.ACTIVITY,
            BLUEPRINT_PRODUCTS.QUANTITY,
            BLUEPRINT_ACTIVITIES.TIME_SECONDS,
        )
            .from(BLUEPRINT_PRODUCTS)
            .join(blueprintType).on(blueprintType.ID.eq(BLUEPRINT_PRODUCTS.BLUEPRINT_TYPE_ID))
            .join(BLUEPRINT_ACTIVITIES).on(
                BLUEPRINT_ACTIVITIES.BLUEPRINT_TYPE_ID.eq(BLUEPRINT_PRODUCTS.BLUEPRINT_TYPE_ID)
                    .and(BLUEPRINT_ACTIVITIES.ACTIVITY.eq(BLUEPRINT_PRODUCTS.ACTIVITY)),
            )
            .where(BLUEPRINT_PRODUCTS.TYPE_ID.eq(product))
            .and(BLUEPRINT_PRODUCTS.ACTIVITY.`in`(RECIPE_ACTIVITIES))
            .and(blueprintType.PUBLISHED.isTrue)
            .orderBy(BLUEPRINT_PRODUCTS.BLUEPRINT_TYPE_ID)
            .fetch()
        if (makers.isEmpty()) return null
        check(makers.size == 1) {
            "Type $product has several published blueprints (${makers.joinToString { it.value1().toString() }}); no rule picks one"
        }
        val maker = makers.single()
        val blueprint = maker.value1()
        val materials = dsl.select(BLUEPRINT_MATERIALS.TYPE_ID, BLUEPRINT_MATERIALS.QUANTITY)
            .from(BLUEPRINT_MATERIALS)
            .where(BLUEPRINT_MATERIALS.BLUEPRINT_TYPE_ID.eq(blueprint))
            .and(BLUEPRINT_MATERIALS.ACTIVITY.eq(maker.value2()))
            .fetch { it.value1() to it.value2() }
            .toMap()
        return Recipe(product, maker.value3(), materials, blueprint, maker.value4())
    }

    private companion object {
        val RECIPE_ACTIVITIES = listOf("manufacturing", "reaction")
    }
}
