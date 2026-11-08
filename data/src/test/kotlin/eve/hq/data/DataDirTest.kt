package eve.hq.data

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataDirTest {
    private val home = Path.of("/home/yury")

    @Test
    fun `on Linux the default is under the XDG default data location`() {
        val platform = Platform(osName = "Linux", userHome = home)

        assertEquals(Path.of("/home/yury/.local/share/eve-hq"), DataDir.resolve(platform))
    }

    @Test
    fun `on Linux XDG_DATA_HOME wins over the default`() {
        val platform = Platform(osName = "Linux", userHome = home, env = mapOf("XDG_DATA_HOME" to "/data/xdg"))

        assertEquals(Path.of("/data/xdg/eve-hq"), DataDir.resolve(platform))
    }

    @Test
    fun `the system property overrides everything, including the environment variable`() {
        val platform = Platform(
            osName = "Linux",
            userHome = home,
            env = mapOf("XDG_DATA_HOME" to "/data/xdg", "EVE_HQ_DATA_DIR" to "/from/env"),
            properties = mapOf("eve.hq.data.dir" to "/from/property"),
        )

        assertEquals(Path.of("/from/property"), DataDir.resolve(platform))
    }

    @Test
    fun `the environment variable overrides the platform default`() {
        val platform = Platform(
            osName = "Linux",
            userHome = home,
            env = mapOf("XDG_DATA_HOME" to "/data/xdg", "EVE_HQ_DATA_DIR" to "/from/env"),
        )

        assertEquals(Path.of("/from/env"), DataDir.resolve(platform))
    }

    @Test
    fun `on macOS the default is under Application Support`() {
        val platform = Platform(osName = "Mac OS X", userHome = Path.of("/Users/yury"))

        assertEquals(Path.of("/Users/yury/Library/Application Support/eve-hq"), DataDir.resolve(platform))
    }

    @Test
    fun `on Windows the default is under LOCALAPPDATA`() {
        val platform = Platform(
            osName = "Windows 11",
            userHome = Path.of("/Users/yury"),
            env = mapOf("LOCALAPPDATA" to "/Users/yury/AppData/Local"),
        )

        assertEquals(Path.of("/Users/yury/AppData/Local/eve-hq"), DataDir.resolve(platform))
    }

    @Test
    fun `on Windows without LOCALAPPDATA it falls back to AppData Local under the home directory`() {
        val platform = Platform(osName = "Windows 11", userHome = Path.of("/Users/yury"))

        assertEquals(Path.of("/Users/yury/AppData/Local/eve-hq"), DataDir.resolve(platform))
    }

    @Test
    fun `opening the data dir creates a missing folder`(@TempDir tmp: Path) {
        val platform = Platform(osName = "Linux", userHome = tmp)

        val dir = DataDir.open(platform)

        assertTrue(Files.isDirectory(dir), "expected $dir to exist")
    }
}
