package eve.hq.app

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserLauncherTest {
    private val uri = URI.create("https://login.eveonline.com/v2/oauth/authorize?state=s")

    @Test
    fun `xdg-open is asked to open the address`() {
        assertEquals(listOf("xdg-open", uri.toString()), BrowserLauncher.xdgOpen(uri).command())
    }

    @Test
    fun `xdg-open runs without desktop detection variables so its KDE branch cannot silently do nothing`() {
        val ambient = mapOf(
            "DISPLAY" to ":0",
            "PATH" to "/usr/bin",
            "XDG_CURRENT_DESKTOP" to "KDE",
            "KDE_FULL_SESSION" to "true",
            "KDE_SESSION_VERSION" to "6",
            "DESKTOP_SESSION" to "plasma",
            "GNOME_DESKTOP_SESSION_ID" to "this-is-deprecated",
        )

        val environment = BrowserLauncher.xdgOpen(uri, ambient).environment()

        assertEquals(mapOf("DISPLAY" to ":0", "PATH" to "/usr/bin"), environment.toMap())
    }
}
