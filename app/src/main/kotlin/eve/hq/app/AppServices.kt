package eve.hq.app

import eve.hq.app.characters.CharactersViewModel
import eve.hq.core.character.AddCharacter
import eve.hq.core.character.ListCharacters
import eve.hq.core.character.RemoveCharacter
import eve.hq.data.AppConfig
import eve.hq.data.FileDatabase
import eve.hq.data.JooqCharacterRepository
import eve.hq.data.sso.EveSsoAuthenticator
import eve.hq.data.sso.SsoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.time.Clock

object AppServices {
    fun characters(dataDir: Path): CharactersViewModel {
        val config = AppConfig.load(dataDir)
        val database = FileDatabase.open(dataDir, Clock.systemUTC())
        val repository = JooqCharacterRepository(database.dataSource)
        val sso = SsoConfig(clientId = config.ssoClientId, scopes = config.ssoScopes)
        val authenticator = EveSsoAuthenticator.create(sso, BrowserLauncher::open)
        return CharactersViewModel(
            ListCharacters(repository),
            AddCharacter(authenticator, repository),
            RemoveCharacter(repository),
            CoroutineScope(SupervisorJob() + Dispatchers.Main),
            Dispatchers.IO,
        )
    }
}
