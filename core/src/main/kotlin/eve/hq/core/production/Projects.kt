package eve.hq.core.production

/** The code-defined projects; the enum entry is a project's id and its [title] what the owner sees. */
enum class ProjectId(val title: String) {
    AmarrShuttles("1000 Amarr Shuttles"),
    AnathemaInputs("Inputs for 500 Anathemas"),
    MediumT2Hulls("Medium T2 hulls"),
    T3Cruisers("T3 cruisers"),
}

/** What a readiness project is configured with: the hulls to be ready for, the cycle's tempo and how many jobs. */
data class ReadinessConfig(val hulls: List<String>, val cycle: CycleSettings, val jobs: Long)

/**
 * The medium T2 hull project's settings; edit them here. The hulls are plain item names (a few, for testing), the speed
 * multiplies quantities as in the v1 notebooks (1.4, to be replaced by real job times later), and the cycle also limits how
 * long a job of the phases below may run. The window keeps the last 12 hours of the cycle free of jobs, so there is time to
 * get to the game and restart them.
 */
/**
 * What a fixed-run project is configured with: the hulls (any one of them is built), how many hulls to be ready to build,
 * and the runs of one invented copy. The count is rounded up to whole copies.
 */
data class FixedRunConfig(val hulls: List<String>, val hullCount: Long, val copyRuns: Long, val cycle: CycleSettings) {
    /** Runs to be ready for: [hullCount] rounded up to a multiple of [copyRuns]. */
    val runs: Long get() = (hullCount + copyRuns - 1) / copyRuns * copyRuns
}

/** The T3 cruiser project's settings; edit them here. 16 hulls of either kind, as 4 copies of 4 runs. */
val T3_CRUISERS = FixedRunConfig(
    hulls = listOf("Legion", "Tengu"),
    hullCount = 16,
    copyRuns = 4,
    cycle = CycleSettings(java.time.Duration.ofDays(14), speed = 1.4, window = java.time.Duration.ofHours(12)),
)

val MEDIUM_T2_HULLS = ReadinessConfig(
    hulls = listOf(
        "Zealot", "Sacrilege", "Deimos", "Ishtar", "Absolution", "Guardian","Oneiros","Curse", "Pilgrim",
        "Arazu", "Lachesis", "Devoter","Phobos",
        "Impel","Prorator","Viator","Occator","Hulk","Skiff","Mackinaw",
        "Absolution","Damnation", "Astarte", "Eos"
    ),
    cycle = CycleSettings(java.time.Duration.ofDays(14), speed = 1.4, window = java.time.Duration.ofHours(12)),
    jobs = 4,
)

class Projects(
    private val items: Items,
    private val recipes: RecipeBook,
    private val mediumT2Hulls: ReadinessConfig = MEDIUM_T2_HULLS,
    private val t3Cruisers: FixedRunConfig = T3_CRUISERS,
) {
    fun get(id: ProjectId): Project = when (id) {
        ProjectId.AmarrShuttles -> FixedRequirementsProject(
            id.title,
            listOf(Requirement(items.id("Amarr Shuttle"), 1_000, "build 1000 shuttles")),
            endProducts = setOf(items.id("Amarr Shuttle")),
        )

        // Everything the hull job needs is a goal: the components are built in phases, and the rest (the hull, the
        // tool, commodities) is only needed on hand, so it is bought.
        ProjectId.AnathemaInputs -> FixedRequirementsProject(
            id.title,
            inputsOfJobs(recipes, items.id("Anathema"), 500, "500 Anathema jobs"),
            endProducts = setOf(items.id("Anathema")),
            buildRule = ::buildsComponentChain,
        )

        // Ready to start N cycle-long jobs of any of the listed hulls; the hull jobs themselves are not planned.
        ProjectId.T3Cruisers -> t3(id)

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
            endProducts = mediumT2Hulls.hulls.mapTo(linkedSetOf(), items::id),
            buildRule = ::buildsComponentChain,
        )
    }

    /** Ready to build the configured number of T3 hulls (whole copies), either kind; the hull jobs are not planned. */
    private fun t3(id: ProjectId) = FixedRequirementsProject(
        id.title,
        fixedRunRequirements(recipes, t3Cruisers.hulls.map(items::id), t3Cruisers.runs, "${t3Cruisers.runs} hulls of either kind"),
        cycle = t3Cruisers.cycle,
        endProducts = t3Cruisers.hulls.mapTo(linkedSetOf(), items::id),
        buildRule = { item -> items.isGroup(item, "Hybrid Tech Components") || items.isGroup(item, "Hybrid Polymers") },
    )

    /** Components and the composites and intermediates under them are built; everything else is bought. */
    private fun buildsComponentChain(item: TypeId) =
        items.isGroup(item, "Construction Components") ||
            items.isGroup(item, "Composite") ||
            items.isGroup(item, "Intermediate Materials")
}
