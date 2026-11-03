package dev.smartrequest.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class HttpTransportTest {

    private lateinit var server: HttpServer
    private val transport = HttpTransport()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/echo") { exchange -> handleEcho(exchange) }
        server.createContext("/gzip") { exchange -> handleGzip(exchange) }
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `sends method headers and body and reads a json response`() {
        val prepared = PreparedRequest(
            method = "POST",
            url = "http://127.0.0.1:${server.address.port}/echo?existing=1",
            headers = listOf(
                "Content-Type" to "application/json",
                "X-Test" to "smart-request"
            ),
            body = """{"name":"Ada"}""",
            timeoutMs = 5_000,
            followRedirects = true
        )

        val response = transport.send(prepared).get(10, TimeUnit.SECONDS)

        assertEquals(200, response.statusCode)
        assertTrue(response.textual)
        assertTrue(response.isJson)
        assertTrue(response.body.contains("\"method\":\"POST\""), response.body)
        assertTrue(response.body.contains("smart-request"), response.body)
        assertTrue(response.body.contains("existing=1"), response.body)
    }

    @Test
    fun `decompresses gzip responses`() {
        val prepared = PreparedRequest(
            method = "GET",
            url = "http://127.0.0.1:${server.address.port}/gzip",
            headers = emptyList(),
            body = null,
            timeoutMs = 5_000,
            followRedirects = true
        )

        val response = transport.send(prepared).get(10, TimeUnit.SECONDS)

        assertEquals(200, response.statusCode)
        assertEquals("""{"compressed":true}""", response.body)
    }

    @Test
    fun `exposes non successful status codes`() {
        val prepared = PreparedRequest(
            method = "GET",
            url = "http://127.0.0.1:${server.address.port}/error",
            headers = emptyList(),
            body = null,
            timeoutMs = 5_000,
            followRedirects = true
        )

        val response = transport.send(prepared).get(10, TimeUnit.SECONDS)

        assertEquals(500, response.statusCode)
        assertEquals("Internal Server Error", response.statusText)
        assertTrue(!response.successful)
    }

    private fun handleEcho(exchange: HttpExchange) {
        val requestBody = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val payload = """
            {"method":"${exchange.requestMethod}","body":${jsonString(requestBody)},"header":${jsonString(exchange.requestHeaders.getFirst("X-Test").orEmpty())},"query":${jsonString(exchange.requestURI.rawQuery.orEmpty())}}
        """.trimIndent()
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleGzip(exchange: HttpExchange) {
        val raw = """{"compressed":true}""".toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        val bytes = output.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Content-Encoding", "gzip")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
