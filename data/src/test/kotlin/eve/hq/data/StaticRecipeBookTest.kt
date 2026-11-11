package eve.hq.data

import eve.hq.core.industry.IndustryActivity
import eve.hq.core.production.Recipe
import org.assertj.core.api.Assertions.assertThat
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StaticRecipeBookTest {
    private val dataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
    }.also(Migrations::migrate)
    private val book = StaticRecipeBook(dataSource)

    private fun sql(statement: String) = dataSource.connection.use { it.createStatement().executeUpdate(statement) }

    init {
        sql("insert into categories (id, name) values (1, 'Category')")
        sql("insert into groups (id, category_id, name) values (1, 1, 'Group')")
    }

    private fun type(id: Long, name: String, published: Boolean = true) =
        sql("insert into types (id, group_id, name, published) values ($id, 1, '$name', $published)")

    /** A blueprint (its own type) making [perRun] of [product] from [materials]; the product type must exist. */
    private fun blueprint(
        id: Long,
        product: Long,
        activity: String = "manufacturing",
        perRun: Int = 1,
        seconds: Int = 600,
        materials: Map<Long, Int> = emptyMap(),
        published: Boolean = true,
    ) {
        type(id, "Blueprint $id", published)
        sql("insert into blueprints (type_id, max_production_limit) values ($id, 10)")
        sql("insert into blueprint_activities (blueprint_type_id, activity, time_seconds) values ($id, '$activity', $seconds)")
        sql("insert into blueprint_products (blueprint_type_id, activity, type_id, quantity) values ($id, '$activity', $product, $perRun)")
        materials.forEach { (material, quantity) ->
            sql("insert into blueprint_materials (blueprint_type_id, activity, type_id, quantity) values ($id, '$activity', $material, $quantity)")
        }
    }

    @Test
    fun `a manufacturing recipe carries its blueprint, run time, output per run and materials`() {
        type(11134, "Amarr Shuttle")
        type(34, "Tritanium")
        blueprint(11135, product = 11134, seconds = 6000, materials = mapOf(34L to 2778))

        assertThat(book.forProduct(11134))
            .isEqualTo(Recipe(11134, 1, mapOf(34L to 2778), blueprint = 11135, secondsPerRun = 6000, activity = IndustryActivity.Manufacturing))
    }

    @Test
    fun `a reaction recipe is read the same way`() {
        type(16634, "Tungsten Carbide")
        type(16633, "Tungsten")
        blueprint(46207, product = 16634, activity = "reaction", perRun = 10_000, seconds = 10_800, materials = mapOf(16633L to 100))

        assertThat(book.forProduct(16634)).isEqualTo(Recipe(16634, 10_000, mapOf(16633L to 100), 46207, 10_800, IndustryActivity.Reaction))
    }

    @Test
    fun `an item no blueprint makes has no recipe`() {
        type(34, "Tritanium")

        assertThat(book.forProduct(34)).isNull()
    }

    @Test
    fun `unpublished blueprints are ignored, so a development blueprint cannot make a product ambiguous`() {
        type(16634, "Tungsten Carbide")
        blueprint(46207, product = 16634, activity = "reaction", seconds = 10_800)
        blueprint(45732, product = 16634, activity = "reaction", seconds = 360, published = false)
        type(2000, "Only Made By Test Blueprint")
        blueprint(2001, product = 2000, published = false)

        assertThat(book.forProduct(16634)?.blueprint).isEqualTo(46207)
        assertThat(book.forProduct(2000)).isNull()
    }

    @Test
    fun `two published blueprints for one product fail loudly instead of picking one`() {
        type(500, "Ambiguous")
        blueprint(501, product = 500)
        blueprint(502, product = 500)

        val failure = assertFailsWith<IllegalStateException> { book.forProduct(500) }

        assertThat(failure.message).contains("500", "501", "502")
    }
}
