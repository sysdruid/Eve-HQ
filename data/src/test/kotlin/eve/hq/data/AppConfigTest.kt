package eve.hq.data

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {
    private fun writeConfig(dir: Path, text: String) = Files.writeString(dir.resolve("config.properties"), text)

    @Test
    fun `the client id is read from config properties in the data dir`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=file-client\n")

        assertEquals(AppConfig(ssoClientId = "file-client"), AppConfig.load(dir, env = emptyMap()))
    }

    @Test
    fun `the environment variable wins over the file`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=file-client\n")

        assertEquals("env-client", AppConfig.load(dir, env = mapOf("EVE_HQ_CLIENT_ID" to "env-client")).ssoClientId)
    }

    @Test
    fun `the environment variable alone is enough, with no file`(@TempDir dir: Path) {
        assertEquals("env-client", AppConfig.load(dir, env = mapOf("EVE_HQ_CLIENT_ID" to "env-client")).ssoClientId)
    }

    @Test
    fun `without a file or variable the error says where to put the client id`(@TempDir dir: Path) {
        val failure = assertFailsWith<MissingConfig> { AppConfig.load(dir, env = emptyMap()) }

        assertTrue("sso.clientId" in failure.message!!, failure.message)
        assertTrue(dir.resolve("config.properties").toString() in failure.message!!, failure.message)
    }

    @Test
    fun `a file without the key is reported the same way`(@TempDir dir: Path) {
        writeConfig(dir, "something.else=1\n")

        assertFailsWith<MissingConfig> { AppConfig.load(dir, env = emptyMap()) }
    }

    @Test
    fun `a blank client id counts as missing`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=   \n")

        assertFailsWith<MissingConfig> { AppConfig.load(dir, env = emptyMap()) }
    }

    @Test
    fun `without sso scopes the asset and structure scopes are requested`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=file-client\n")

        assertEquals(AppConfig.DEFAULT_SCOPES, AppConfig.load(dir, env = emptyMap()).ssoScopes)
    }

    @Test
    fun `scopes are read from the file, separated by spaces or commas`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=c\nsso.scopes=publicData, esi-skills.read_skills.v1  esi-assets.read_assets.v1\n")

        assertEquals(
            listOf(
                "publicData",
                "esi-skills.read_skills.v1",
                "esi-assets.read_assets.v1",
                "esi-universe.read_structures.v1",
            ),
            AppConfig.load(dir, env = emptyMap()).ssoScopes,
        )
    }

    @Test
    fun `a blank scopes line falls back to public data`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=c\nsso.scopes=  ,  \n")

        assertEquals(AppConfig.DEFAULT_SCOPES, AppConfig.load(dir, env = emptyMap()).ssoScopes)
    }

    @Test
    fun `asset and structure scopes are included even when custom scopes are configured`(@TempDir dir: Path) {
        writeConfig(dir, "sso.clientId=c\nsso.scopes=publicData\n")

        assertEquals(
            listOf("publicData", "esi-assets.read_assets.v1", "esi-universe.read_structures.v1"),
            AppConfig.load(dir, env = emptyMap()).ssoScopes,
        )
    }
}
