package eve.hq.core.production

/** The code-defined projects; the enum entry is a project's id and its [title] what the owner sees. */
enum class ProjectId(val title: String) {
    AmarrShuttles("1000 Amarr Shuttles"),
    AnathemaInputs("Inputs for 500 Anathemas"),
    MediumT2Hulls("Medium T2 hulls"),
}

/** What a readiness project is configured with: the hulls to be ready for, the cycle's tempo and how many jobs. */
data class ReadinessConfig(val hulls: List<String>, val cycle: CycleSettings, val jobs: Long)

/**
 * The medium T2 hull project's settings; edit them here. The hulls are plain item names (a few, for testing), the speed
 * multiplies quantities as in the v1 notebooks (1.4, to be replaced by real job times later), and the cycle also limits how
 * long a job of the phases below may run.
 */
val MEDIUM_T2_HULLS = ReadinessConfig(
    hulls = listOf("Zealot", "Sacrilege", "Deimos", "Ishtar", "Absolution", "Impel"),
    cycle = CycleSettings(java.time.Duration.ofDays(14), speed = 1.4),
    jobs = 10,
)

class Projects(
    private val items: Items,
    private val recipes: RecipeBook,
    private val mediumT2Hulls: ReadinessConfig = MEDIUM_T2_HULLS,
) {
    fun get(id: ProjectId): Project = when (id) {
        ProjectId.AmarrShuttles -> FixedRequirementsProject(
            id.title,
            listOf(Requirement(items.id("Amarr Shuttle"), 1_000, "build 1000 shuttles")),
        )

        // Everything the hull job needs is a goal: the components are built in phases, and the rest (the hull, the
        // tool, commodities) is only needed on hand, so it is bought.
        ProjectId.AnathemaInputs -> FixedRequirementsProject(
            id.title,
            inputsOfJobs(recipes, items.id("Anathema"), 500, "500 Anathema jobs"),
            buildRule = ::buildsComponentChain,
        )

        // Ready to start N cycle-long jobs of any of the listed hulls; the hull jobs themselves are not planned.
        ProjectId.MediumT2Hulls -> FixedRequirementsProject(
            id.title,
            readinessRequirements(
                recipes,
                mediumT2Hulls.hulls.map(items::id),
                mediumT2Hulls.cycle,
                mediumT2Hulls.jobs,
                "${mediumT2Hulls.jobs} cycle jobs of any of ${mediumT2Hulls.hulls.size} hulls",
            ),
            cycle = mediumT2Hulls.cycle,
            buildRule = ::buildsComponentChain,
        )
    }

    /** Components and the composites and intermediates under them are built; everything else is bought. */
    private fun buildsComponentChain(item: TypeId) =
        items.isGroup(item, "Construction Components") ||
            items.isGroup(item, "Composite") ||
            items.isGroup(item, "Intermediate Materials")
}
