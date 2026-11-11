package eve.hq.core.production

import eve.hq.core.industry.IndustryActivity
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class CachedTimeRulesTest {
    private var bonusLookups = 0
    private var requiredLookups = 0
    private val rules = CachedTimeRules(
        object : TimeRules {
            override fun bonus(type: TypeId, attribute: Int): Double? {
                bonusLookups++
                return if (type == 3380L && attribute == 440) -4.0 else null
            }

            override fun requiredSkills(blueprint: TypeId, activity: IndustryActivity): Map<TypeId, Int> {
                requiredLookups++
                return mapOf(3380L to 1)
            }
        },
    )

    @Test
    fun `an answer is fetched once, including that there is none`() {
        repeat(3) {
            assertThat(rules.bonus(3380, 440)).isEqualTo(-4.0)
            assertThat(rules.bonus(3380, 1982)).isNull()
        }

        assertThat(bonusLookups).isEqualTo(2)
    }

    @Test
    fun `the skills a blueprint requires are fetched once per blueprint and activity`() {
        repeat(3) {
            assertThat(rules.requiredSkills(100, IndustryActivity.Manufacturing)).containsEntry(3380L, 1)
        }
        rules.requiredSkills(100, IndustryActivity.Reaction)
        rules.requiredSkills(101, IndustryActivity.Manufacturing)

        assertThat(requiredLookups).isEqualTo(3)
    }
}
