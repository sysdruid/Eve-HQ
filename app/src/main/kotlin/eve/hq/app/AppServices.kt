package eve.hq.app

import eve.hq.app.assets.AssetsViewModel
import eve.hq.app.blueprints.BlueprintsViewModel
import eve.hq.app.projects.ProjectsViewModel
import eve.hq.app.stockpiles.StockpilesViewModel
import eve.hq.app.characters.CharactersViewModel
import eve.hq.app.jobs.JobsViewModel
import eve.hq.core.asset.AssetService
import eve.hq.core.asset.ListAssets
import eve.hq.core.asset.RefreshingAssetSource
import eve.hq.core.blueprint.ListBlueprints
import eve.hq.core.production.Items
import eve.hq.core.production.ListStockpiles
import eve.hq.core.production.PlanProject
import eve.hq.core.production.Projects
import eve.hq.core.blueprint.RefreshingBlueprintSource
import eve.hq.core.character.AddCharacter
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.RefreshCharacterToken
import eve.hq.core.character.RemoveCharacter
import eve.hq.core.industry.ListJobs
import eve.hq.core.industry.RefreshingJobSource
import eve.hq.data.AppConfig
import eve.hq.data.FileDatabase
import eve.hq.data.JooqAssetCacheRepository
import eve.hq.data.JooqBlueprintPageCache
import eve.hq.data.JooqStockpileRepository
import eve.hq.data.StaticItemCatalog
import eve.hq.data.StaticRecipeBook
import eve.hq.data.JooqCharacterRepository
import eve.hq.data.JooqJobPageCache
import eve.hq.data.JooqLocationNameCache
import eve.hq.data.StaticBlueprintRepository
import eve.hq.data.StaticTypeNameRepository
import eve.hq.data.TokenCipher
import eve.hq.data.esi.EveAssetSource
import eve.hq.data.esi.EveBlueprintSource
import eve.hq.data.esi.EveJobSource
import eve.hq.data.sso.EveSsoAuthenticator
import eve.hq.data.sso.EveTokenRefresher
import eve.hq.data.sso.SsoConfig
import eve.hq.data.sso.SsoTokenClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.time.Clock

data class AppViewModels(
    val characters: CharactersViewModel,
    val assets: Lazy<AssetsViewModel>,
    val jobs: Lazy<JobsViewModel>,
    val blueprints: Lazy<BlueprintsViewModel>,
    val stockpiles: Lazy<StockpilesViewModel>,
    val projects: Lazy<ProjectsViewModel>,
)

object AppServices {
    fun create(dataDir: Path): AppViewModels {
        val config = AppConfig.load(dataDir)
        val clock = Clock.systemUTC()
        val database = FileDatabase.open(dataDir, clock)
        val repository = JooqCharacterRepository(database.dataSource, TokenCipher.loadOrCreate(dataDir))
        val assetCache = JooqAssetCacheRepository(database.dataSource)
        val blueprintPageCache = JooqBlueprintPageCache(database.dataSource)
        val locationNames = JooqLocationNameCache(database.dataSource)
        val staticBlueprints = StaticBlueprintRepository(database.dataSource)
        val jobPageCache = JooqJobPageCache(database.dataSource)
        val staticTypeNames = StaticTypeNameRepository(database.dataSource)
        val sso = SsoConfig(clientId = config.ssoClientId, scopes = config.ssoScopes)
        val authenticator = EveSsoAuthenticator.create(sso, BrowserLauncher::open)
        val listCharacters = ListCharacters(repository)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val characters = CharactersViewModel(
            listCharacters,
            AddCharacter(authenticator, repository),
            RemoveCharacter(repository),
            scope,
            Dispatchers.IO,
        )
        val http = lazy { HttpClient(CIO) }
        val refreshToken = lazy { RefreshCharacterToken(repository, EveTokenRefresher(SsoTokenClient(http.value, sso))) }
        val assetSource = lazy {
            RefreshingAssetSource(
                repository,
                refreshToken.value,
                clock,
                EveAssetSource(http.value, staticTypeNames = staticTypeNames, assetCache = assetCache, clock = clock, locationNames = locationNames),
            )
        }
        val assetService = lazy { AssetService(assetSource.value, clock) }
        val assets = lazy { AssetsViewModel(listCharacters, ListAssets(assetService.value), scope, Dispatchers.IO) }
        val stockpileRepository = JooqStockpileRepository(database.dataSource)
        val stockpiles = lazy {
            StockpilesViewModel(
                listCharacters,
                ListStockpiles(stockpileRepository, assetService.value),
                stockpileRepository,
                staticTypeNames,
                scope,
                Dispatchers.IO,
            )
        }
        val jobSource = lazy {
            RefreshingJobSource(
                repository,
                refreshToken.value,
                clock,
                EveJobSource(http.value, staticTypeNames = staticTypeNames, pageCache = jobPageCache, clock = clock, locationNames = locationNames),
            )
        }
        val jobs = lazy {
            JobsViewModel(listCharacters, ListJobs(jobSource.value, clock), clock, scope, Dispatchers.IO)
        }
        val blueprints = lazy {
            val source = RefreshingBlueprintSource(
                repository,
                refreshToken.value,
                clock,
                EveBlueprintSource(
                    http.value,
                    staticTypeNames = staticTypeNames,
                    staticBlueprints = staticBlueprints,
                    locationNames = locationNames,
                    pageCache = blueprintPageCache,
                    clock = clock,
                ),
            )
            BlueprintsViewModel(listCharacters, ListBlueprints(source, jobSource.value), clock, scope, Dispatchers.IO)
        }
        val staticItems = StaticItemCatalog(database.dataSource)
        val recipeBook = StaticRecipeBook(database.dataSource)
        val projects = lazy {
            ProjectsViewModel(
                listCharacters,
                PlanProject(Projects(Items(staticItems), recipeBook), recipeBook, assetService.value),
                staticItems,
                scope,
                Dispatchers.IO,
            )
        }
        return AppViewModels(characters, assets, jobs, blueprints, stockpiles, projects)
    }
}
