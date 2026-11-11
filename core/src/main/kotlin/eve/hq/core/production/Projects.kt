package eve.hq.core.production

/** The code-defined projects; the enum entry is a project's id and its [title] what the owner sees. */
enum class ProjectId(val title: String) {
    AmarrShuttles("1000 Amarr Shuttles"),
    AnathemaInputs("Inputs for 10 Anathemas"),
}

class Projects(private val items: Items, private val recipes: RecipeBook) {
    fun get(id: ProjectId): Project = when (id) {
        ProjectId.AmarrShuttles -> FixedRequirementsProject(
            id.title,
            listOf(Requirement(items.id("Amarr Shuttle"), 1_000, "build 1000 shuttles")),
        )

        // Everything the hull job needs is a goal: the components are built in phases, and the rest (the hull, the
        // tool, commodities) is only needed on hand, so it is bought.
        ProjectId.AnathemaInputs -> FixedRequirementsProject(
            id.title,
            inputsOfJobs(recipes, items.id("Anathema"), 10, "10 Anathema jobs"),
        ) { item ->
            items.isGroup(item, "Construction Components") ||
                items.isGroup(item, "Composite") ||
                items.isGroup(item, "Intermediate Materials")
        }
    }
}
