package eve.hq.app

import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

object BrowserLauncher {
    fun open(uri: URI) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
            return
        }
        val process = xdgOpen(uri).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw IOException("xdg-open could not open $uri")
        }
    }

    // With XDG_CURRENT_DESKTOP=KDE, xdg-open 1.1.x runs `kfmclient exec` and reports success even when that is
    // not installed, so nothing opens and no error is raised. Without desktop detection it falls back to the
    // default handler for https links, which is what we want.
    internal fun xdgOpen(uri: URI, environment: Map<String, String> = System.getenv()): ProcessBuilder {
        val builder = ProcessBuilder("xdg-open", uri.toString())
        builder.environment().apply {
            clear()
            putAll(environment.filterKeys { it !in DESKTOP_DETECTION })
        }
        return builder
    }

    private val DESKTOP_DETECTION = setOf(
        "XDG_CURRENT_DESKTOP",
        "KDE_FULL_SESSION",
        "KDE_SESSION_VERSION",
        "DESKTOP_SESSION",
        "GNOME_DESKTOP_SESSION_ID",
    )
}
