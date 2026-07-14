package utils

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

class PmcViewerImportServer(
    private val port: Int = 18082,
    private val onImported: (DbActivity) -> Unit
) {
    private var server: HttpServer? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (server != null) return
        try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/api/import", ImportHandler())
                executor = Executors.newSingleThreadExecutor()
                start()
            }
            println("PMC Viewer Import Server started on port $port")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        println("PMC Viewer Import Server stopped")
    }

    val isRunning: Boolean
        get() = server != null

    inner class ImportHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendResponse(exchange, 405, "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}")
                return
            }

            try {
                val reader = BufferedReader(InputStreamReader(exchange.requestBody, Charsets.UTF_8))
                val body = reader.readText()
                val act = json.decodeFromString<DbActivity>(body)
                
                DatabaseManager.saveActivity(act)
                onImported(act)
                
                sendResponse(exchange, 200, "{\"status\":\"ok\",\"message\":\"Activity imported successfully\"}")
            } catch (e: Exception) {
                e.printStackTrace()
                sendResponse(exchange, 400, "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}")
            }
        }

        private fun sendResponse(exchange: HttpExchange, status: Int, response: String) {
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
