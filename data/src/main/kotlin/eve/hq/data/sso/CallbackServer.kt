package eve.hq.data.sso

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import eve.hq.core.character.LoginFailed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.time.Duration

class CallbackServer private constructor(
    private val servers: List<HttpServer>,
    private val result: CompletableDeferred<String>,
) : AutoCloseable {
    val port: Int get() = servers.first().address.port

    suspend fun awaitCode(timeout: Duration): String =
        withTimeoutOrNull(timeout) { result.await() } ?: throw LoginFailed("timed out waiting for the browser login")

    override fun close() = servers.forEach { it.stop(0) }

    companion object {
        // Browsers resolve "localhost" to ::1 or 127.0.0.1 and some (here: Chromium) do not fall back after
        // a refused connection, so listen on both. IPv4 is required; IPv6 is best effort.
        fun start(port: Int, expectedState: String): CallbackServer {
            val result = CompletableDeferred<String>()
            val handler = HttpHandler { exchange -> handle(exchange, expectedState, result) }
            val ipv4 = try {
                HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
            } catch (e: BindException) {
                throw LoginFailed("port $port is in use; close what is using it or change the callback port", e)
            }
            val ipv6 = try {
                HttpServer.create(InetSocketAddress(InetAddress.getByName("::1"), ipv4.address.port), 0)
            } catch (e: IOException) {
                null
            }
            val servers = listOfNotNull(ipv4, ipv6)
            servers.forEach { it.createContext("/callback", handler); it.start() }
            return CallbackServer(servers, result)
        }

        private fun handle(exchange: HttpExchange, expectedState: String, result: CompletableDeferred<String>) {
            val query = parse(exchange.requestURI.rawQuery)
            when {
                query["state"] != expectedState -> respond(exchange, 400, "Unexpected login attempt.")
                "error" in query -> {
                    result.completeExceptionally(LoginFailed("login not granted: ${query["error"]} ${query["error_description"].orEmpty()}".trim()))
                    respond(exchange, 400, "Login was not completed. You can close this window.")
                }
                else -> {
                    result.complete(query.getValue("code"))
                    respond(exchange, 200, "Login complete. You can close this window.")
                }
            }
        }

        private fun parse(rawQuery: String?): Map<String, String> = rawQuery.orEmpty().split("&").filter { "=" in it }.associate {
            val (key, value) = it.split("=", limit = 2)
            key to URLDecoder.decode(value, Charsets.UTF_8)
        }

        private fun respond(exchange: HttpExchange, status: Int, text: String) {
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
