package dev.smartrequest.model

import java.util.UUID

/**
 * Serializable project state. Keep this model deliberately simple and backward compatible:
 * IntelliJ Platform stores it in `.idea/smart-request.xml` through [dev.smartrequest.service.SmartRequestProjectService].
 */
class SmartRequestState {
    var version: Int = 1
    var environments: MutableList<EnvironmentConfig> = mutableListOf()
    var activeEnvironmentId: String? = null

    /**
     * Project-wide globals used before they moved into [EnvironmentConfig]. They are read only to
     * migrate old `.idea/smart-request.xml` files into the active environment and are empty after
     * that; new writes always go to the environment.
     */
    @Deprecated("Moved to EnvironmentConfig.globalHeaders")
    var globalHeaders: MutableList<KeyValue> = mutableListOf()

    /** @see globalHeaders */
    @Deprecated("Moved to EnvironmentConfig.globalParams")
    var globalParams: MutableList<KeyValue> = mutableListOf()

    var groups: MutableList<RequestGroup> = mutableListOf()
    var history: MutableList<RequestHistoryEntry> = mutableListOf()
    var timeoutMs: Int = DEFAULT_TIMEOUT_MS
    var followRedirects: Boolean = true

    fun normalize() {
        if (version < 1) version = 1

        if (environments.isEmpty()) {
            environments.add(EnvironmentConfig(name = "local", baseUrl = "http://localhost:8080"))
        }
        environments.forEach { it.normalize() }
        if (environments.none { it.id == activeEnvironmentId }) {
            activeEnvironmentId = environments.first().id
        }

        // Project-wide globals from older versions belong to whichever environment was active.
        migrateLegacyGlobals()
        if (version < 2) version = 2
        if (timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) timeoutMs = DEFAULT_TIMEOUT_MS

        if (groups.isEmpty()) {
            groups.add(RequestGroup(name = "My Requests"))
        }
        groups.forEach { it.normalize() }
        if (history.size > MAX_HISTORY) {
            history = history.take(MAX_HISTORY).toMutableList()
        }
    }

    @Suppress("DEPRECATION")
    private fun migrateLegacyGlobals() {
        if (globalHeaders.isEmpty() && globalParams.isEmpty()) return
        val environment = activeEnvironment() ?: return
        mergeMissing(environment.globalHeaders, globalHeaders)
        mergeMissing(environment.globalParams, globalParams)
        globalHeaders.clear()
        globalParams.clear()
    }

    private fun mergeMissing(target: MutableList<KeyValue>, source: List<KeyValue>) {
        val existing = target.map { it.key.trim().lowercase() }.toSet()
        source.forEach { value ->
            val key = value.key.trim()
            if (key.isNotEmpty() && key.lowercase() !in existing) {
                target.add(value.deepCopy())
            }
        }
    }

    fun activeEnvironment(): EnvironmentConfig? =
        environments.firstOrNull { it.id == activeEnvironmentId } ?: environments.firstOrNull()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 600_000
        const val MAX_HISTORY = 100

        fun createDefault(): SmartRequestState = SmartRequestState().apply {
            environments.add(EnvironmentConfig(name = "local", baseUrl = "http://localhost:8080"))
            activeEnvironmentId = environments.first().id
            groups.add(
                RequestGroup(name = "My Requests").apply {
                    requests.add(
                        HttpRequestConfig(
                            name = "Health check",
                            method = "GET",
                            url = "{{baseUrl}}/actuator/health"
                        )
                    )
                }
            )
        }
    }
}

class EnvironmentConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "local",
    var baseUrl: String = "http://localhost:8080",
    var variables: MutableList<KeyValue> = mutableListOf(),
    var globalHeaders: MutableList<KeyValue> = mutableListOf(),
    var globalParams: MutableList<KeyValue> = mutableListOf()
) {
    fun normalize() {
        if (name.isBlank()) name = "environment"
        variables.removeAll { it.key.isBlank() && it.value.isNotBlank() }
        globalHeaders.removeAll { it.key.isBlank() && it.value.isNotBlank() }
        globalParams.removeAll { it.key.isBlank() && it.value.isNotBlank() }
    }

    fun deepCopy(): EnvironmentConfig = EnvironmentConfig().also { copy ->
        copy.id = id
        copy.name = name
        copy.baseUrl = baseUrl
        copy.variables = variables.map { it.deepCopy() }.toMutableList()
        copy.globalHeaders = globalHeaders.map { it.deepCopy() }.toMutableList()
        copy.globalParams = globalParams.map { it.deepCopy() }.toMutableList()
    }
}

class RequestGroup(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Group",
    var expanded: Boolean = true,
    var requests: MutableList<HttpRequestConfig> = mutableListOf()
) {
    fun normalize() {
        if (name.isBlank()) name = "Group"
        requests.forEach { it.normalize() }
    }

    fun deepCopy(): RequestGroup = RequestGroup().also { copy ->
        copy.id = id
        copy.name = name
        copy.expanded = expanded
        copy.requests = requests.map { it.deepCopy() }.toMutableList()
    }
}

class HttpRequestConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Request",
    var method: String = "GET",
    var url: String = "{{baseUrl}}/",
    var params: MutableList<KeyValue> = mutableListOf(),
    var headers: MutableList<KeyValue> = mutableListOf(),
    var pathParams: MutableList<KeyValue> = mutableListOf(),
    var bodyType: String = BodyType.NONE.name,
    var body: String = "",
    var springKey: String? = null,
    var extractors: MutableList<ResponseExtractor> = mutableListOf()
) {
    fun normalize() {
        method = method.uppercase().takeIf { it in HTTP_METHODS } ?: "GET"
        if (name.isBlank()) name = "New Request"
        if (BodyType.entries.none { it.name == bodyType }) bodyType = BodyType.NONE.name
        extractors.removeAll { it.variable.isBlank() && it.source.isBlank() }
    }

    fun bodyTypeEnum(): BodyType = BodyType.entries.firstOrNull { it.name == bodyType } ?: BodyType.NONE

    fun deepCopy(newId: Boolean = false): HttpRequestConfig = HttpRequestConfig().also { copy ->
        copy.id = if (newId) UUID.randomUUID().toString() else id
        copy.name = name
        copy.method = method
        copy.url = url
        copy.params = params.map { it.deepCopy() }.toMutableList()
        copy.headers = headers.map { it.deepCopy() }.toMutableList()
        copy.pathParams = pathParams.map { it.deepCopy() }.toMutableList()
        copy.bodyType = bodyType
        copy.body = body
        copy.springKey = springKey
        copy.extractors = extractors.map { it.deepCopy() }.toMutableList()
    }

    companion object {
        val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    }
}

/**
 * A rule that copies a value out of a response into an environment variable.
 *
 * Rules belong to the request (not to the response view) because they are part of the request
 * definition: they are saved with the request, they make request chaining possible (`{{token}}` in
 * the next request), and they are evaluated again on every response.
 *
 * [source] uses the syntax documented on [dev.smartrequest.extract.ResponseExtractorEngine]:
 * `data.a.b`, `data.items[0].id`, `data["a.b"]`, `headers.X-Request-Id`, `status`, `statusText`.
 */
class ResponseExtractor(
    var id: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var variable: String = "",
    var source: String = ""
) {
    fun deepCopy(): ResponseExtractor = ResponseExtractor().also { copy ->
        copy.id = id
        copy.enabled = enabled
        copy.variable = variable
        copy.source = source
    }

    fun isBlank(): Boolean = variable.isBlank() && source.isBlank()
}

class KeyValue(
    var key: String = "",
    var value: String = "",
    var enabled: Boolean = true
) {
    fun deepCopy(): KeyValue = KeyValue().also { copy ->
        copy.key = key
        copy.value = value
        copy.enabled = enabled
    }

    companion object {
        fun of(key: String, value: String, enabled: Boolean = true): KeyValue = KeyValue().apply {
            this.key = key
            this.value = value
            this.enabled = enabled
        }
    }
}

/**
 * A single completed HTTP exchange. The request snapshot is stored so a history entry remains usable
 * even after the original request is edited or deleted.
 */
class RequestHistoryEntry(
    var id: String = UUID.randomUUID().toString(),
    var timestamp: Long = 0L,
    var requestName: String = "",
    var method: String = "GET",
    var url: String = "",
    var environmentName: String = "",
    var statusCode: Int = 0,
    var statusText: String = "",
    var durationMs: Long = 0L,
    var responseSize: Long = 0L,
    var errorMessage: String? = null,
    var request: HttpRequestConfig = HttpRequestConfig()
) {
    val failed: Boolean get() = errorMessage != null || statusCode == 0 || statusCode >= 400
}

enum class BodyType(val label: String, val contentType: String?) {
    NONE("None", null),
    JSON("JSON", "application/json; charset=UTF-8"),
    TEXT("Text", "text/plain; charset=UTF-8"),
    XML("XML", "application/xml; charset=UTF-8"),
    FORM("Form URL Encoded", "application/x-www-form-urlencoded; charset=UTF-8")
}

fun List<KeyValue>.enabledValues(): List<KeyValue> = filter { it.enabled && it.key.isNotBlank() }

fun List<KeyValue>.withoutBlankKeys(): MutableList<KeyValue> =
    filter { it.key.isNotBlank() }.map { it.deepCopy() }.toMutableList()
