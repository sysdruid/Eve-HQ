package eve.hq.core.production

/**
 * A level of [item] wanted on hand at cycle start, and why. [required] is the hard need and sizes jobs; [preferred]
 * is the level at which the owner can relax about an item they obtain elsewhere, and only affects the shopping list.
 */
data class Requirement(val item: TypeId, val required: Long, val because: String, val preferred: Long? = null) {
    init {
        require(required >= 0) { "Required level must not be negative" }
        require(preferred == null || preferred >= required) { "Preferred level must not be below the required level" }
    }
}

/**
 * What a project needs and which items it builds rather than buys. How the requirements come about (a fixed list, a
 * readiness rule, ...) is the project's own business; the planner only sees the result.
 */
interface Project {
    val name: String

    /** Whether the project builds [item] from its inputs; if not, the item is bought (or otherwise obtained). */
    fun builds(item: TypeId): Boolean

    fun requirements(): List<Requirement>

    /** The items this project exists to produce (the hulls); running jobs making them belong to it. */
    val endProducts: Set<TypeId> get() = emptySet()

    /** The cycle this project plans in; its jobs are split so none runs longer than the cycle. Null: no limit. */
    val cycle: CycleSettings? get() = null
}

/** The simplest project: a fixed list of requirements. */
class FixedRequirementsProject(
    override val name: String,
    private val required: List<Requirement>,
    override val cycle: CycleSettings? = null,
    override val endProducts: Set<TypeId> = emptySet(),
    private val buildRule: (TypeId) -> Boolean = { true },
) : Project {
    override fun builds(item: TypeId) = buildRule(item)

    override fun requirements() = required
}
