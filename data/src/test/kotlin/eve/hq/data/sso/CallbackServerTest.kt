package eve.hq.data.sso

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import eve.hq.core.character.LoginFailed
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CallbackServerTest {
    private val browser = HttpClient.newHttpClient()

    private fun visit(server: CallbackServer, pathAndQuery: String): HttpResponse<String> =
        browser.send(
            HttpRequest.newBuilder(URI.create("http://localhost:${server.port}$pathAndQuery")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a callback with the expected state delivers the code and tells the user they can close the window`() {
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            val page = visit(server, "/callback?code=the-code&state=state-1")

            assertEquals(200, page.statusCode())
            assertEquals("the-code", runBlocking { server.awaitCode(5.seconds) })
        }
    }

    @Test
    fun `a callback with the wrong state is refused but does not spoil the login`() {
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            val stale = visit(server, "/callback?code=stale-code&state=an-earlier-attempt")
            visit(server, "/callback?code=the-code&state=state-1")

            assertEquals(400, stale.statusCode())
            assertEquals("the-code", runBlocking { server.awaitCode(5.seconds) })
        }
    }

    @Test
    fun `a denied login fails with the reason`() {
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            visit(server, "/callback?error=access_denied&error_description=User+cancelled&state=state-1")

            val failure = assertFailsWith<LoginFailed> { runBlocking { server.awaitCode(5.seconds) } }
            assertTrue("access_denied" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `no callback within the timeout fails the login`() {
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            val failure = assertFailsWith<LoginFailed> {
                runBlocking { withTimeout(3.seconds) { server.awaitCode(100.milliseconds) } }
            }

            assertTrue("timed out" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `a port that is already in use gives a clear error`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { occupied ->
            val failure = assertFailsWith<LoginFailed> {
                CallbackServer.start(port = occupied.localPort, expectedState = "state-1")
            }

            assertTrue("${occupied.localPort}" in failure.message!!, failure.message)
        }
    }

    @Test
    fun `a browser asking for the favicon does not disturb the login`() {
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            val favicon = visit(server, "/favicon.ico")
            visit(server, "/callback?code=the-code&state=state-1")

            assertEquals(404, favicon.statusCode())
            assertEquals("the-code", runBlocking { server.awaitCode(5.seconds) })
        }
    }

    private fun visitAddress(address: String, port: Int): Int = browser.send(
        HttpRequest.newBuilder(URI.create("http://$address:$port/callback?code=c&state=state-1")).build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    @Test
    fun `the callback is reachable on both the IPv4 and IPv6 loopback, because browsers resolve localhost to either`() {
        assumeTrue(runCatching { ServerSocket(0, 1, InetAddress.getByName("::1")).close() }.isSuccess, "no IPv6 loopback here")
        CallbackServer.start(port = 0, expectedState = "state-1").use { server ->
            assertEquals(200, visitAddress("127.0.0.1", server.port))
            assertEquals(200, visitAddress("[::1]", server.port))
        }
    }
}
