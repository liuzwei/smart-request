package dev.smartrequest.http

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.enabledValues

data class PreparedRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val timeoutMs: Int,
    val followRedirects: Boolean
)

data class PrepareResult(
    val request: PreparedRequest,
    val warnings: List<String>
)

/**
 * Turns the editable request model into a concrete HTTP request. All variables are resolved here, before
 * anything is sent, so validation errors can be shown to the user without a network round trip.
 */
object RequestPreparer {
    private val RESERVED_HEADERS = setOf(
        "connection", "content-length", "expect", "host", "upgrade", "transfer-encoding"
    )

    fun prepare(
        request: HttpRequestConfig,
        environment: EnvironmentConfig?,
        globalHeaders: List<KeyValue>,
        globalParams: List<KeyValue>,
        timeoutMs: Int,
        followRedirects: Boolean
    ): PrepareResult {
        val variables = VariableResolver.environmentVariables(environment)
        val warnings = LinkedHashSet<String>()

        val mergedParams = mergeParameters(request.params, globalParams)
        val urlResult = RequestUrlBuilder.build(request.url, mergedParams, variables, request.pathParams)
        warnings += urlResult.unresolved

        val mergedHeaders = mergeHeaders(request.headers, globalHeaders)
        val finalHeaders = LinkedHashMap<String, String>()
        for ((key, value) in mergedHeaders) {
            val resolvedKey = VariableResolver.resolve(key, variables)
            val resolvedValue = VariableResolver.resolve(value, variables)
            warnings += resolvedKey.unresolved
            warnings += resolvedValue.unresolved
            val normalizedKey = resolvedKey.text.trim()
            if (normalizedKey.isEmpty() || normalizedKey.lowercase() in RESERVED_HEADERS) continue
            finalHeaders[normalizedKey] = resolvedValue.text
        }

        val bodyType = request.bodyTypeEnum()
        val body = if (bodyType == BodyType.NONE) {
            null
        } else {
            val resolvedBody = VariableResolver.resolve(request.body, variables)
            warnings += resolvedBody.unresolved
            resolvedBody.text
        }

        if (warnings.isNotEmpty()) {
            throw RequestBuildException("Unresolved variables: ${warnings.joinToString(", ")}")
        }

        if (bodyType.contentType != null && finalHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            finalHeaders["Content-Type"] = bodyType.contentType
        }
        if (finalHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            finalHeaders["User-Agent"] = "Smart-Request/1.0"
        }

        return PrepareResult(
            PreparedRequest(
                method = request.method.uppercase(),
                url = urlResult.url,
                headers = finalHeaders.map { it.key to it.value },
                body = body,
                timeoutMs = timeoutMs,
                followRedirects = followRedirects
            ),
            warnings.toList()
        )
    }

    /**
     * Request parameters always win over global parameters with the same name. Duplicate request parameters
     * are preserved so a caller can intentionally send repeated keys.
     */
    fun mergeParameters(request: List<KeyValue>, global: List<KeyValue>): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val result = ArrayList<Pair<String, String>>()
        request.enabledValues().forEach {
            result += it.key to it.value
            seen += it.key.trim().lowercase()
        }
        global.enabledValues().forEach {
            if (it.key.trim().lowercase() !in seen) {
                result += it.key to it.value
                seen += it.key.trim().lowercase()
            }
        }
        return result
    }

    fun mergeHeaders(request: List<KeyValue>, global: List<KeyValue>): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val result = ArrayList<Pair<String, String>>()
        request.enabledValues().forEach {
            result += it.key to it.value
            seen += it.key.trim().lowercase()
        }
        global.enabledValues().forEach {
            if (it.key.trim().lowercase() !in seen) {
                result += it.key to it.value
                seen += it.key.trim().lowercase()
            }
        }
        return result
    }
}
