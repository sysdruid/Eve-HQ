package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** The SDE does not change while the app runs, so each answer is fetched once. */
class CachedTimeRules(private val delegate: TimeRules) : TimeRules {
    private val bonuses = ConcurrentHashMap<Pair<TypeId, Int>, Optional<Double>>()
    private val required = ConcurrentHashMap<Pair<TypeId, IndustryActivity>, Map<TypeId, Int>>()

    // An absent answer is remembered too: most skills have no bonus, and asking again would be the common case.
    override fun bonus(type: TypeId, attribute: Int): Double? =
        bonuses.getOrPut(type to attribute) { Optional.ofNullable(delegate.bonus(type, attribute)) }.orElse(null)

    override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity): Map<TypeId, Int> =
        required.getOrPut(blueprint to activity) { delegate.requiredSkills(blueprint, activity) }
}
