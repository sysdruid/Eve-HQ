package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity

private const val FASTER_STRUCTURE = 35_825L

/** Run times where every manufacturing job takes [reduction] less than its base time (a structure with that bonus). */
fun runTimesFasterBy(reduction: Double): RunTimes = RunTimes(
    object : TimeRules {
        override fun bonus(type: TypeId, attribute: Int) =
            if (type == FASTER_STRUCTURE && attribute == TimeAttributes.ENGINEERING_STRUCTURE_TIME) 1.0 - reduction else null

        override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity) = emptyMap<TypeId, Int>()
    },
    traits = emptyList(),
    structures = setOf(FASTER_STRUCTURE),
)
