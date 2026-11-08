package eve.hq.app

import eve.hq.app.assets.AssetsViewModel
import eve.hq.app.characters.CharactersViewModel
import eve.hq.core.asset.ListAssets
import eve.hq.core.asset.RefreshingAssetSource
import eve.hq.core.character.AddCharacter
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.RefreshCharacterToken
import eve.hq.core.character.RemoveCharacter
import eve.hq.data.AppConfig
import eve.hq.data.FileDatabase
import eve.hq.data.JooqAssetCacheRepository
import eve.hq.data.JooqCharacterRepository
import eve.hq.data.StaticTypeNameRepository
import eve.hq.data.TokenCipher
import eve.hq.data.esi.EveAssetSource
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

data class AppViewModels(val characters: CharactersViewModel, val assets: Lazy<AssetsViewModel>)

object AppServices {
    fun create(dataDir: Path): AppViewModels {
        val config = AppConfig.load(dataDir)
        val clock = Clock.systemUTC()
        val database = FileDatabase.open(dataDir, clock)
        val repository = JooqCharacterRepository(database.dataSource, TokenCipher.loadOrCreate(dataDir))
        val assetCache = JooqAssetCacheRepository(database.dataSource)
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
        val assets = lazy {
            val http = HttpClient(CIO)
            val refresher = EveTokenRefresher(SsoTokenClient(http, sso))
            val source = RefreshingAssetSource(
                repository,
                RefreshCharacterToken(repository, refresher),
                clock,
                EveAssetSource(http, staticTypeNames = staticTypeNames, assetCache = assetCache, clock = clock),
            )
            AssetsViewModel(listCharacters, ListAssets(source), scope, Dispatchers.IO)
        }
        return AppViewModels(characters, assets)
    }
}
