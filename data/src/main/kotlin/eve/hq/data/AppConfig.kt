package eve.hq.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class MissingConfig(message: String) : Exception(message)

data class AppConfig(val ssoClientId: String) {
    companion object {
        const val FILE = "config.properties"
        const val CLIENT_ID_ENV = "EVE_HQ_CLIENT_ID"
        private const val CLIENT_ID_KEY = "sso.clientId"

        fun load(dataDir: Path, env: Map<String, String> = System.getenv()): AppConfig {
            val file = dataDir.resolve(FILE)
            val clientId = env[CLIENT_ID_ENV]?.trim()?.takeIf(String::isNotEmpty)
                ?: readProperty(file, CLIENT_ID_KEY)
                ?: throw MissingConfig("No EVE client id. Set $CLIENT_ID_KEY=<your client id> in $file or the $CLIENT_ID_ENV environment variable.")
            return AppConfig(ssoClientId = clientId)
        }

        private fun readProperty(file: Path, key: String): String? {
            if (!Files.isRegularFile(file)) return null
            val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
            return properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        }
    }
}
