package eve.hq.core.production

import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import kotlin.test.Test

class PlanProjectTest {
    private val shuttle = 11134L
    private val tritanium = 34L
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
    private val catalog = FakeItemCatalog(names = mapOf(shuttle to "Amarr Shuttle", tritanium to "Tritanium"))
    private val recipes = RecipeBook { product ->
        Recipe(shuttle, 1, mapOf(tritanium to 2778), blueprint = 11135, secondsPerRun = 6000).takeIf { product == shuttle }
    }
    private val holdings = mutableMapOf<Long, Result<List<AssetItem>>>()
    private val service = AssetService(AssetSource { holdings.getValue(it.id).getOrThrow() }, Clock.systemUTC())
    private val planProject = PlanProject(
        Projects(Items(catalog), recipes),
        recipes,
        service,
        eve.hq.core.industry.ListJobs({ emptyList() }, Clock.systemUTC()),
        Clock.systemUTC(),
    )

    private fun tritanium(itemId: Long, quantity: Int) =
        AssetItem(itemId, AssetType(tritanium, "Tritanium"), null, quantity, AssetLocation(60000001, "Jita IV"))

    @Test
    fun `the shuttle project is a thousand runs against stock pooled over all characters`() = runBlocking<Unit> {
        holdings[alice.id] = Result.success(listOf(tritanium(1, 600)))
        holdings[bob.id] = Result.success(listOf(tritanium(2, 400)))

        val result = planProject(ProjectId.AmarrShuttles, listOf(alice, bob))

        assertThat(result.plan.jobs).containsExactly(PlannedJob(shuttle, 1_000, setOf("1000 Amarr Shuttles"), 1, 11135, 6000))
        val shopping = result.plan.prerequisites.single()
        assertThat(shopping.item).isEqualTo(tritanium)
        assertThat(shopping.required).isEqualTo(2_778_000)
        assertThat(shopping.onHand).isEqualTo(1_000)
        assertThat(shopping.deficit).isEqualTo(2_777_000)
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `a character whose assets fail is reported and counts as holding nothing`() = runBlocking<Unit> {
        holdings[alice.id] = Result.failure(java.io.IOException("ESI down"))

        val result = planProject(ProjectId.AmarrShuttles, listOf(alice))

        assertThat(result.failures.single().message).isEqualTo("ESI down")
        assertThat(result.plan.prerequisites.single().onHand).isZero()
    }
}
