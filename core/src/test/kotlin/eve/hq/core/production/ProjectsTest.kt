package eve.hq.core.production

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProjectsTest {
    private val catalog = FakeItemCatalog(names = mapOf(11134L to "Amarr Shuttle", 34L to "Tritanium"))

    @Test
    fun `the shuttle project requires ten shuttles`() {
        val project = Projects(Items(catalog)).get(ProjectId.AmarrShuttles)

        assertThat(project.name).isEqualTo("10 Amarr Shuttles")
        assertThat(project.requirements()).containsExactly(Requirement(11134, 10, "build 10 shuttles"))
    }

    @Test
    fun `a project whose item is missing from the catalog fails when it is defined`() {
        val withoutShuttle = Projects(Items(FakeItemCatalog(names = mapOf(34L to "Tritanium"))))

        assertFailsWith<IllegalArgumentException> { withoutShuttle.get(ProjectId.AmarrShuttles) }
    }
}
