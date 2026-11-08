package eve.hq.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class MissingConfig(message: String) : Exception(message)

data class AppConfig(
    val ssoClientId: String,
    val ssoScopes: List<String> = DEFAULT_SCOPES,
) {
    companion object {
        val REQUIRED_SCOPES = listOf("esi-assets.read_assets.v1", "esi-universe.read_structures.v1")
        val DEFAULT_SCOPES = listOf("publicData") + REQUIRED_SCOPES
        const val FILE = "config.properties"
        const val CLIENT_ID_ENV = "EVE_HQ_CLIENT_ID"
        private const val CLIENT_ID_KEY = "sso.clientId"
        private const val SCOPES_KEY = "sso.scopes"

        fun load(dataDir: Path, env: Map<String, String> = System.getenv()): AppConfig {
            val file = dataDir.resolve(FILE)
            val clientId = env[CLIENT_ID_ENV]?.trim()?.takeIf(String::isNotEmpty)
                ?: readProperty(file, CLIENT_ID_KEY)
                ?: throw MissingConfig("No EVE client id. Set $CLIENT_ID_KEY=<your client id> in $file or the $CLIENT_ID_ENV environment variable.")
            val scopes = readProperty(file, SCOPES_KEY).orEmpty().split(Regex("[\\s,]+")).filter(String::isNotEmpty)
            return AppConfig(
                ssoClientId = clientId,
                ssoScopes = (scopes.ifEmpty { DEFAULT_SCOPES } + REQUIRED_SCOPES).distinct(),
            )
        }

        private fun readProperty(file: Path, key: String): String? {
            if (!Files.isRegularFile(file)) return null
            val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
            return properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        }
    }
}
