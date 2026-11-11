package eve.hq.core.blueprint

import eve.hq.core.industry.IndustryActivity
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import kotlin.test.Test

class SearchBlueprintsTest {
    private val rifter = row(1, "Alice", "Rifter Blueprint", "Jita IV - Moon 4")
    private val merlinBusy = row(2, "Bob", "Merlin Blueprint", "Amarr VIII", use = BlueprintUse(IndustryActivity.Copying, Instant.EPOCH))
    private val rows = listOf(rifter, merlinBusy)

    @Test
    fun `a blank query keeps every row`() {
        assertThat(SearchBlueprints()(rows, query = " ", freeOnly = false)).containsExactly(rifter, merlinBusy)
    }

    @Test
    fun `the query matches blueprint name, character or location without regard to case`() {
        assertThat(SearchBlueprints()(rows, "RIFTER", false)).containsExactly(rifter)
        assertThat(SearchBlueprints()(rows, "bob", false)).containsExactly(merlinBusy)
        assertThat(SearchBlueprints()(rows, "amarr", false)).containsExactly(merlinBusy)
    }

    @Test
    fun `free only hides blueprints that a job is using`() {
        assertThat(SearchBlueprints()(rows, "", freeOnly = true)).containsExactly(rifter)
    }

    private fun row(id: Long, character: String, name: String, location: String, use: BlueprintUse? = null) =
        BlueprintRow(id, character, name, BlueprintKind.Original, 0, 0, null, location, use, blueprintTypeId = 0)
}
