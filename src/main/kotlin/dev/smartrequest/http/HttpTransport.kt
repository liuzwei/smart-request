package dev.smartrequest.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

data class HttpResponseData(
    val statusCode: Int,
    val statusText: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val bodyBytes: Long,
    val durationMs: Long,
    val contentType: String?,
    val truncated: Boolean,
    val textual: Boolean
) {
    val successful: Boolean get() = statusCode in 200..399
    val isJson: Boolean
        get() {
            if (contentType?.contains("json", ignoreCase = true) == true) return true
            if (contentType != null) return false
            val trimmed = body.trimStart()
            return trimmed.startsWith("{") || trimmed.startsWith("[")
        }
}

class HttpTransport(
    private val executor: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "smart-request-http").apply { isDaemon = true }
    }
) {
    /**
     * Sends [prepared] with a one-off client that is closed when the exchange completes. Use the
     * overload below when several requests run together (a scenario run) and should share one
     * client, which keeps the TLS handshakes down to one per run.
     */
    fun send(prepared: PreparedRequest): CompletableFuture<HttpResponseData> {
        val client = createClient(
            followRedirects = prepared.followRedirects,
            connectTimeoutMs = prepared.timeoutMs.coerceAtMost(MAX_CONNECT_TIMEOUT_MS)
        )
        return try {
            send(prepared, client).whenComplete { _, _ -> client.close() }
        } catch (e: Throwable) {
            client.close()
            throw e
        }
    }

    /**
     * Sends [prepared] with [client]. The caller owns the client: it is neither closed on success nor
     * on failure, so the same client can send the next request of a scenario run.
     */
    fun send(prepared: PreparedRequest, client: HttpClient): CompletableFuture<HttpResponseData> {
        val startedAt = System.nanoTime()
        val requestBuilder = HttpRequest.newBuilder(URI.create(prepared.url))
            .timeout(Duration.ofMillis(prepared.timeoutMs.toLong()))

        prepared.headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        val bodyPublisher = if (prepared.body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(prepared.body, StandardCharsets.UTF_8)
        }
        requestBuilder.method(prepared.method, bodyPublisher)

        return client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            .thenApplyAsync({ response ->
                val headers = flattenHeaders(response)
                val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
                val contentEncoding = headers
                    .firstOrNull { it.first.equals("Content-Encoding", ignoreCase = true) }
                    ?.second
                    ?.lowercase()

                val stream = decodeContent(response.body(), contentEncoding)
                val bytes = stream.use { readLimited(it, MAX_BODY_BYTES) }
                val textual = isTextual(contentType, bytes.data)
                val charset = charsetFrom(contentType)
                val bodyText = if (textual) {
                    String(bytes.data, charset)
                } else {
                    ""
                }

                HttpResponseData(
                    statusCode = response.statusCode(),
                    statusText = reasonPhrase(response.statusCode()),
                    headers = headers,
                    body = bodyText,
                    bodyBytes = bytes.totalBytes,
                    durationMs = (System.nanoTime() - startedAt) / 1_000_000,
                    contentType = contentType,
                    truncated = bytes.truncated,
                    textual = textual
                )
            }, executor)
    }

    private fun flattenHeaders(response: HttpResponse<*>): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        response.headers().map().forEach { (name, values) ->
            values.forEach { result += name to it }
        }
        return result
    }

    private fun decodeContent(stream: InputStream, contentEncoding: String?): InputStream = when (contentEncoding) {
        "gzip", "x-gzip" -> GZIPInputStream(stream)
        "deflate" -> InflaterInputStream(stream)
        else -> stream
    }

    private data class LimitedBytes(
        val data: ByteArray,
        val totalBytes: Long,
        val truncated: Boolean
    )

    private fun readLimited(stream: InputStream, maxBytes: Int): LimitedBytes {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        var truncated = false
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (output.size() < maxBytes) {
                val allowed = minOf(read, maxBytes - output.size())
                output.write(buffer, 0, allowed)
                if (allowed < read) {
                    truncated = true
                    break
                }
            } else {
                truncated = true
                break
            }
        }
        return LimitedBytes(output.toByteArray(), total, truncated)
    }

    private fun isTextual(contentType: String?, bytes: ByteArray): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null) {
            return mime.startsWith("text/") ||
                    mime.contains("json") ||
                    mime.contains("xml") ||
                    mime.contains("javascript") ||
                    mime.contains("x-www-form-urlencoded") ||
                    mime.contains("graphql") ||
                    mime.contains("yaml")
        }
        val probe = bytes.take(512)
        return probe.none { it == 0.toByte() }
    }

    private fun charsetFrom(contentType: String?): Charset {
        if (contentType != null) {
            val match = Regex("""charset\s*=\s*"?([^";\s]+)"""", RegexOption.IGNORE_CASE)
                .find(contentType)
            if (match != null) {
                runCatching { return Charset.forName(match.groupValues[1]) }
            }
        }
        return StandardCharsets.UTF_8
    }

    companion object {
        const val MAX_BODY_BYTES = 5 * 1024 * 1024
        const val MAX_CONNECT_TIMEOUT_MS = 15_000

        /**
         * A reusable client. [HttpClient] is thread-safe and keeps connections alive, so a scenario
         * run creates one and sends every node through it. The caller must close it when done:
         * [java.net.http.HttpClient.shutdownNow] when a run is cancelled, [java.net.http.HttpClient.close]
         * otherwise.
         */
        fun createClient(
            followRedirects: Boolean,
            connectTimeoutMs: Int = MAX_CONNECT_TIMEOUT_MS
        ): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs.coerceAtLeast(1).toLong()))
            .followRedirects(
                if (followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER
            )
            .build()

        fun describeError(error: Throwable): String {
            val cause = unwrap(error)
            val message = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
            val detail = cause.message?.takeIf { it.isNotBlank() && it != cause.javaClass.simpleName }
            return when (cause) {
                is java.net.http.HttpConnectTimeoutException -> "Connection timed out: $message"
                is HttpTimeoutException -> "Request timed out: $message"
                is java.net.ConnectException -> "Could not connect" + (detail?.let { ": $it" } ?: " (connection refused)")
                is java.net.UnknownHostException -> "Unknown host: $message"
                is javax.net.ssl.SSLException -> "TLS error: $message"
                else -> message
            }
        }

        fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is CompletionException || current is java.util.concurrent.ExecutionException) &&
                current.cause != null
            ) {
                current = current.cause!!
            }
            return current
        }

        fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> ""
        }
    }
}
