package eve.hq.core.production

import eve.hq.core.asset.AssetLoadFailure
import eve.hq.core.asset.AssetService
import eve.hq.core.character.EveCharacter

/** A project's plan, and the asset loads that failed while working out what is on hand (so stock may read low). */
data class ProjectPlan(val plan: Plan, val failures: List<AssetLoadFailure>)

class PlanProject(
    private val projects: Projects,
    private val recipes: RecipeBook,
    private val assets: AssetService,
) {
    /** Plans the project alone against the pooled assets of [characters]; manual stockpiles are not part of it. */
    suspend operator fun invoke(id: ProjectId, characters: List<EveCharacter>, refresh: Boolean = false): ProjectPlan {
        val owned = assets.assets(characters, refresh = refresh)
        return ProjectPlan(PlanProduction(recipes)(listOf(projects.get(id)), stock = owned.quantities()), owned.failures)
    }
}
