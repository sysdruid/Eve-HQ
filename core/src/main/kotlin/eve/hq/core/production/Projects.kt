package eve.hq.core.production

/** The code-defined projects; the enum entry is a project's id and its [title] what the owner sees. */
enum class ProjectId(val title: String) {
    AmarrShuttles("10 Amarr Shuttles"),
}

class Projects(private val items: Items) {
    fun get(id: ProjectId): Project = when (id) {
        ProjectId.AmarrShuttles -> FixedRequirementsProject(
            id.title,
            listOf(Requirement(items.id("Amarr Shuttle"), 10, "build 10 shuttles")),
        )
    }
}
