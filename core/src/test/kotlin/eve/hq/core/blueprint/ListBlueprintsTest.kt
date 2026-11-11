package eve.hq.core.blueprint

import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetType
import eve.hq.core.character.AuthToken
import eve.hq.core.character.EveCharacter
import eve.hq.core.industry.IndustryActivity
import eve.hq.core.industry.IndustryJob
import eve.hq.core.industry.JobFacility
import eve.hq.core.industry.JobItem
import eve.hq.core.industry.JobSource
import eve.hq.core.industry.JobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ListBlueprintsTest {
    private val alice = EveCharacter(1, "Alice", AuthToken("access", "refresh", Instant.MAX))
    private val noJobs = JobSource { emptyList() }

    @Test
    fun `given no characters, the list is empty and nothing is loaded`() = runBlocking<Unit> {
        val result = ListBlueprints(
            { error("blueprints must not be loaded") },
            { error("jobs must not be loaded") },
        )(emptyList())

        assertThat(result).isEqualTo(BlueprintListing(emptyList(), emptyList()))
    }

    @Test
    fun `a row shows the owner, blueprint, kind, efficiencies, remaining runs and location`() = runBlocking<Unit> {
        val source = BlueprintSource {
            listOf(
                aBlueprint(1001, "Rifter Blueprint", BlueprintKind.Original, me = 10, te = 20, runs = null),
                aBlueprint(1002, "Rifter Blueprint", BlueprintKind.Copy, me = 2, te = 4, runs = 4),
            )
        }

        val result = ListBlueprints(source, noJobs)(listOf(alice))

        assertThat(result.rows).containsExactly(
            BlueprintRow(1001, "Alice", "Rifter Blueprint", BlueprintKind.Original, 10, 20, null, "Jita IV - Moon 4", null, 681),
            BlueprintRow(1002, "Alice", "Rifter Blueprint", BlueprintKind.Copy, 2, 4, 4, "Jita IV - Moon 4", null, 681),
        )
        assertThat(result.failures).isEmpty()
    }

    @Test
    fun `unresolved names show ids, and rows are ordered by character then blueprint name`() = runBlocking<Unit> {
        val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
        val source = BlueprintSource { character ->
            if (character.id == alice.id) {
                listOf(aBlueprint(1, "Zephyr Blueprint"), aBlueprint(2, "Atron Blueprint"))
            } else {
                listOf(OwnedBlueprint(3, AssetType(777, null), BlueprintKind.Original, 0, 0, null, null, AssetLocation(99, null)))
            }
        }

        val result = ListBlueprints(source, noJobs)(listOf(bob, alice))

        assertThat(result.rows.map { it.characterName to it.blueprintName }).containsExactly(
            "Alice" to "Atron Blueprint",
            "Alice" to "Zephyr Blueprint",
            "Bob" to "Unknown type (777)",
        )
        assertThat(result.rows.last().locationName).isEqualTo("Location 99")
    }

    @Test
    fun `a blueprint that a running job uses is marked in use with that job's activity and end`() = runBlocking<Unit> {
        val ends = Instant.parse("2026-11-08T14:00:00Z")
        val source = BlueprintSource {
            listOf(aBlueprint(1001), aBlueprint(1002), aBlueprint(1003))
        }
        val jobs = JobSource {
            listOf(
                aJob(blueprintId = 1001, activity = IndustryActivity.Manufacturing, endsAt = ends),
                aJob(blueprintId = 1002, status = JobStatus.Delivered),
            )
        }

        val result = ListBlueprints(source, jobs)(listOf(alice))

        assertThat(result.rows.associate { it.itemId to it.use }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                1001L to BlueprintUse(IndustryActivity.Manufacturing, ends),
                1002L to null,
                1003L to null,
            ),
        )
    }

    @Test
    fun `a character whose blueprints cannot be loaded is reported and the others are still listed`() = runBlocking<Unit> {
        val bob = EveCharacter(2, "Bob", AuthToken("access", "refresh", Instant.MAX))
        val source = BlueprintSource { character ->
            if (character.id == alice.id) error("ESI is down") else listOf(aBlueprint(2))
        }

        val result = ListBlueprints(source, noJobs)(listOf(alice, bob))

        assertThat(result.rows.map(BlueprintRow::characterName)).containsExactly("Bob")
        assertThat(result.failures).containsExactly(BlueprintLoadFailure(1, "Alice", "ESI is down"))
    }

    @Test
    fun `when only the jobs cannot be loaded the blueprints are listed as free with a warning`() = runBlocking<Unit> {
        val source = BlueprintSource { listOf(aBlueprint(1001)) }
        val jobs = JobSource { error("jobs unavailable") }

        val result = ListBlueprints(source, jobs)(listOf(alice))

        assertThat(result.rows.map(BlueprintRow::itemId)).containsExactly(1001L)
        assertThat(result.rows.single().use).isNull()
        assertThat(result.failures.single().message).containsIgnoringCase("in-use").contains("jobs unavailable")
    }

    @Test
    fun `cancelling the load is not reported as a character failure`() {
        val source = BlueprintSource { throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { runBlocking { ListBlueprints(source, noJobs)(listOf(alice)) } }
    }

    @Test
    fun `cached blueprints are listed with in-use marks from cached jobs, without loading anything`() {
        val ends = Instant.parse("2026-11-08T14:00:00Z")
        val source = object : BlueprintSource {
            override suspend fun load(character: EveCharacter): List<OwnedBlueprint> = error("blueprints must not be loaded")
            override fun cached(character: EveCharacter) = listOf(aBlueprint(1001), aBlueprint(1002))
        }
        val jobs = object : JobSource {
            override suspend fun load(character: EveCharacter): List<IndustryJob> = error("jobs must not be loaded")
            override fun cached(character: EveCharacter) = listOf(aJob(blueprintId = 1001, endsAt = ends))
        }

        val result = ListBlueprints(source, jobs).cached(listOf(alice))

        assertThat(result.rows.associate { it.itemId to it.use?.endsAt }).containsExactlyInAnyOrderEntriesOf(
            mapOf(1001L to ends, 1002L to null),
        )
    }

    @Test
    fun `characters without cached blueprints are left out of the cached list`() {
        val source = object : BlueprintSource {
            override suspend fun load(character: EveCharacter): List<OwnedBlueprint> = error("must not load")
        }

        assertThat(ListBlueprints(source, noJobs).cached(listOf(alice)).rows).isEmpty()
    }

    private fun aJob(
        blueprintId: Long,
        activity: IndustryActivity = IndustryActivity.Manufacturing,
        endsAt: Instant = Instant.parse("2026-11-08T14:00:00Z"),
        status: JobStatus = JobStatus.Active,
    ) = IndustryJob(
        jobId = 1,
        activity = activity,
        blueprintId = blueprintId,
        blueprint = JobItem(681, "Rifter Blueprint"),
        product = null,
        runs = 1,
        startedAt = endsAt.minusSeconds(3600),
        endsAt = endsAt,
        status = status,
        facility = JobFacility(60003760, "Jita IV - Moon 4"),
    )

    private fun aBlueprint(
        itemId: Long,
        name: String = "Rifter Blueprint",
        kind: BlueprintKind = BlueprintKind.Original,
        me: Int = 0,
        te: Int = 0,
        runs: Int? = null,
    ) = OwnedBlueprint(
        itemId = itemId,
        type = AssetType(681, name),
        kind = kind,
        materialEfficiency = me,
        timeEfficiency = te,
        remainingRuns = runs,
        maxRuns = 300,
        location = AssetLocation(60003760, "Jita IV - Moon 4"),
    )
}
