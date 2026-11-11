package eve.hq.core.production

/** A level of [item] wanted on hand at cycle start, and why. */
data class Requirement(val item: TypeId, val quantity: Long, val because: String)

/**
 * What a project needs and what it buys instead of building. How the requirements come about (a fixed list, a
 * readiness rule, ...) is the project's own business; the planner only sees the result.
 */
interface Project {
    val name: String

    /** Items this project never expands into their own inputs. */
    val stopItems: Set<TypeId>

    fun requirements(): List<Requirement>
}

/** The simplest project: a fixed list of requirements. */
class FixedRequirementsProject(
    override val name: String,
    private val required: List<Requirement>,
    override val stopItems: Set<TypeId> = emptySet(),
) : Project {
    override fun requirements() = required
}
