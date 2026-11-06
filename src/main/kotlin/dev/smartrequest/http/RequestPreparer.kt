package dev.smartrequest.http

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.enabledValues
import dev.smartrequest.template.FunctionRegistry
import dev.smartrequest.template.TemplateEngine

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
        followRedirects: Boolean,
        variables: Map<String, String> = emptyMap(),
        registry: FunctionRegistry = FunctionRegistry.builtIns()
    ): PrepareResult {
        // Run-scoped values (scenario parameters and captured values) win over the environment, so a
        // scenario can pass a token downstream without mutating the environment.
        val resolvedVariables = VariableResolver.mergeVariables(
            VariableResolver.environmentVariables(environment),
            variables
        )
        val warnings = LinkedHashSet<String>()
        val errors = ArrayList<String>()

        val mergedParams = mergeParameters(
            request.params,
            if (request.useGlobalParams) globalParams else emptyList()
        )
        val urlResult = RequestUrlBuilder.build(
            request.url,
            mergedParams,
            resolvedVariables,
            request.pathParams,
            registry
        )
        warnings += urlResult.unresolved
        errors += urlResult.errors

        val mergedHeaders = mergeHeaders(
            request.headers,
            if (request.useGlobalHeaders) globalHeaders else emptyList()
        )
        val finalHeaders = LinkedHashMap<String, String>()
        for ((key, value) in mergedHeaders) {
            val resolvedKey = TemplateEngine.resolve(key, resolvedVariables, registry)
            val resolvedValue = TemplateEngine.resolve(value, resolvedVariables, registry)
            warnings += resolvedKey.unresolved
            warnings += resolvedValue.unresolved
            errors += resolvedKey.errors
            errors += resolvedValue.errors
            val normalizedKey = resolvedKey.text.trim()
            if (normalizedKey.isEmpty() || normalizedKey.lowercase() in RESERVED_HEADERS) continue
            finalHeaders[normalizedKey] = resolvedValue.text
        }

        val bodyType = request.bodyTypeEnum()
        val body = if (bodyType == BodyType.NONE) {
            null
        } else {
            val resolvedBody = TemplateEngine.resolve(request.body, resolvedVariables, registry)
            warnings += resolvedBody.unresolved
            errors += resolvedBody.errors
            resolvedBody.text
        }

        if (errors.isNotEmpty()) {
            throw RequestBuildException(errors.distinct().joinToString("; "))
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
