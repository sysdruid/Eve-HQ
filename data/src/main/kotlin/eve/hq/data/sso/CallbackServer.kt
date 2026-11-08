package eve.hq.data.sso

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import eve.hq.core.character.LoginFailed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.time.Duration

class CallbackServer private constructor(
    private val server: HttpServer,
    private val result: CompletableDeferred<String>,
) : AutoCloseable {
    val port: Int get() = server.address.port

    suspend fun awaitCode(timeout: Duration): String =
        withTimeoutOrNull(timeout) { result.await() } ?: throw LoginFailed("timed out waiting for the browser login")

    override fun close() = server.stop(0)

    companion object {
        fun start(port: Int, expectedState: String): CallbackServer {
            val result = CompletableDeferred<String>()
            val server = try {
                HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
            } catch (e: BindException) {
                throw LoginFailed("port $port is in use; close what is using it or change the callback port", e)
            }
            server.createContext("/callback") { exchange ->
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
            server.start()
            return CallbackServer(server, result)
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
