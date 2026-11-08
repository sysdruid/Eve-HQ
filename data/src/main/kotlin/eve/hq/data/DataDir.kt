package eve.hq.data

import java.nio.file.Files
import java.nio.file.Path

/** What the data-dir decision depends on, so tests can supply it instead of the real OS. */
data class Platform(
    val osName: String,
    val userHome: Path,
    val env: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
) {
    companion object {
        fun current() = Platform(
            osName = System.getProperty("os.name"),
            userHome = Path.of(System.getProperty("user.home")),
            env = System.getenv(),
            properties = System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) },
        )
    }
}

object DataDir {
    const val PROPERTY = "eve.hq.data.dir"
    const val ENV = "EVE_HQ_DATA_DIR"

    fun open(platform: Platform): Path = resolve(platform).also { Files.createDirectories(it) }

    fun resolve(platform: Platform): Path {
        val override = platform.properties[PROPERTY] ?: platform.env[ENV]
        if (override != null) return Path.of(override)
        return defaultBase(platform).resolve("eve-hq")
    }

    private fun defaultBase(platform: Platform): Path {
        val os = platform.osName.lowercase()
        return when {
            "win" in os && "darwin" !in os ->
                platform.env["LOCALAPPDATA"]?.let(Path::of) ?: platform.userHome.resolve("AppData/Local")
            "mac" in os || "darwin" in os -> platform.userHome.resolve("Library/Application Support")
            else -> platform.env["XDG_DATA_HOME"]?.let(Path::of) ?: platform.userHome.resolve(".local/share")
        }
    }
}
