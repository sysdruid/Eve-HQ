package eve.hq.app

import java.awt.Desktop
import java.io.IOException
import java.net.URI

object BrowserLauncher {
    fun open(uri: URI) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri)
            return
        }
        // Desktop.browse is often unavailable on Linux without GNOME libraries; xdg-open is the usual fallback.
        val process = ProcessBuilder("xdg-open", uri.toString()).redirectErrorStream(true).start()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw IOException("xdg-open could not open $uri")
        }
    }
}
