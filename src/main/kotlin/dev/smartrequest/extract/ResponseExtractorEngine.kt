package dev.smartrequest.extract

import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor

/**
 * One evaluated rule, before it is written to the environment. Kept separate from
 * [ExtractionResult] so evaluation (which needs the response) can run off the UI thread while the
 * write-back happens on it.
 */
data class ExtractedValue(
    val extractorId: String,
    val variable: String,
    val source: String,
    val value: String? = null,
    val error: String? = null
) {
    val successful: Boolean get() = error == null && value != null
}

/** Outcome of one rule after the write-back, including what changed in the environment. */
data class ExtractionResult(
    val extractorId: String,
    val variable: String,
    val source: String,
    val value: String? = null,
    val error: String? = null,
    val previous: String? = null,
    val added: Boolean = false,
    val reEnabled: Boolean = false
) {
    val successful: Boolean get() = error == null && value != null
}

/** All outcomes of one run, kept in memory only: results must not churn `.idea/smart-request.xml`. */
data class ExtractionReport(val results: List<ExtractionResult> = emptyList()) {

    val successCount: Int get() = results.count { it.successful }
    val failureCount: Int get() = results.count { !it.successful }
    val addedCount: Int get() = results.count { it.successful && it.added }
    val updatedCount: Int get() = results.count { it.successful && !it.added }
    val isEmpty: Boolean get() = results.isEmpty()

    fun resultFor(extractorId: String): ExtractionResult? =
        results.firstOrNull { it.extractorId == extractorId }

    companion object {
        val EMPTY = ExtractionReport()
    }
}

/**
 * Evaluates Extract rules against a response and writes the captured values into the environment.
 *
 * Rules are declarative on purpose: there is no scripting engine, so the values are predictable and
 * every failure can be explained in one line in the UI.
 *
 * Supported sources:
 * - `data`, `$`, `body` – the JSON body
 * - `data.user.id`, `data.items[0].id`, `data.items[-1].id`, `data["user.name"]`
 * - `headers.X-Request-Id`, `header.Content-Type`
 * - `status`, `statusCode`, `statusText`
 *
 * A failing rule never overwrites an existing variable: a broken response must not destroy a token
 * that the next request depends on.
 */
object ResponseExtractorEngine {

    /** A value stored under this name would never resolve: the environment host always wins. */
    const val RESERVED_VARIABLE = "baseUrl"

    /**
     * Pure evaluation step: no state is touched, so it can run on a background thread right after the
     * response arrives. Rules that are disabled or completely empty are ignored.
     */
    fun extract(response: HttpResponseData?, extractors: List<ResponseExtractor>): List<ExtractedValue> {
        val active = extractors.filter { it.enabled && !it.isBlank() }
        if (active.isEmpty()) return emptyList()

        var bodyParsed = false
        var bodyRoot: JsonNode? = null

        fun parsedBody(): JsonNode? {
            if (!bodyParsed) {
                bodyParsed = true
                bodyRoot = response?.body?.let { JsonReader.parse(it) }
            }
            return bodyRoot
        }

        return active.map { extractor ->
            evaluate(extractor, response, ::parsedBody)
        }
    }

    private fun evaluate(
        extractor: ResponseExtractor,
        response: HttpResponseData?,
        parsedBody: () -> JsonNode?
    ): ExtractedValue {
        val source = extractor.source.trim()
        val variable = resolveVariableName(extractor.variable, source)
        val base = ExtractedValue(extractor.id, variable, source)

        if (source.isEmpty()) return base.copy(error = "Expression is empty")
        if (variable.isEmpty()) return base.copy(error = "Variable name is empty")
        if (variable.any { it.isWhitespace() || it == '{' || it == '}' }) {
            return base.copy(error = "Variable name must not contain spaces or braces")
        }
        if (variable == RESERVED_VARIABLE) {
            return base.copy(error = "$RESERVED_VARIABLE is reserved for the environment host")
        }

        val path = try {
            JsonPath.parse(source)
        } catch (e: PathSyntaxException) {
            return base.copy(error = e.message ?: "Invalid expression")
        }

        return when (path.root) {
            PathRoot.DATA -> {
                if (response == null) return base.copy(error = "No response yet")
                if (!response.textual) return base.copy(error = "Response body is binary")
                if (response.truncated) {
                    return base.copy(error = "Response body was truncated, the value may be missing")
                }
                val root = parsedBody() ?: return base.copy(error = "Response body is not valid JSON")
                when (val outcome = JsonPath.evaluate(path, root)) {
                    is Evaluation.Found -> base.copy(value = outcome.value)
                    is Evaluation.Failure -> base.copy(error = outcome.message)
                }
            }

            PathRoot.HEADERS -> {
                if (response == null) return base.copy(error = "No response yet")
                val name = path.headerName.orEmpty()
                val value = response.headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
                    ?: return base.copy(error = "Header '$name' is not present")
                base.copy(value = value)
            }

            PathRoot.STATUS -> {
                if (response == null) return base.copy(error = "No response yet")
                base.copy(value = response.statusCode.toString())
            }

            PathRoot.STATUS_TEXT -> {
                if (response == null) return base.copy(error = "No response yet")
                base.copy(value = response.statusText)
            }
        }
    }

    /**
     * Writes [values] into [environment]. Existing entries are updated in place (including disabled
     * ones, which are re-enabled) and missing entries are appended, which is what makes an extracted
     * value usable as `{{name}}` from any later request.
     */
    fun apply(environment: EnvironmentConfig?, values: List<ExtractedValue>): ExtractionReport {
        if (values.isEmpty()) return ExtractionReport.EMPTY
        if (environment == null) {
            return ExtractionReport(
                values.map {
                    ExtractionResult(
                        it.extractorId,
                        it.variable,
                        it.source,
                        it.value,
                        it.error ?: "No active environment to write to"
                    )
                }
            )
        }

        val results = values.map { value ->
            if (!value.successful) {
                ExtractionResult(value.extractorId, value.variable, value.source, value.value, value.error)
            } else {
                write(environment, value)
            }
        }
        return ExtractionReport(results)
    }

    private fun write(environment: EnvironmentConfig, value: ExtractedValue): ExtractionResult {
        val key = value.variable.trim()
        val captured = value.value.orEmpty()
        val matches = environment.variables.filter { it.key.trim() == key }
        if (matches.isEmpty()) {
            environment.variables.add(KeyValue.of(key, captured))
            return ExtractionResult(
                value.extractorId,
                key,
                value.source,
                captured,
                added = true
            )
        }

        val previous = matches.first().value
        var reEnabled = false
        matches.forEach { entry ->
            entry.value = captured
            if (!entry.enabled) {
                entry.enabled = true
                reEnabled = true
            }
        }
        return ExtractionResult(
            value.extractorId,
            key,
            value.source,
            captured,
            previous = previous,
            reEnabled = reEnabled
        )
    }

    /**
     * The variable name a rule writes to: the explicit name when present, otherwise a name derived
     * from the last path segment (`data.user.id` → `id`, `headers.X-Request-Id` → `X_Request_Id`).
     */
    fun resolveVariableName(variable: String, source: String): String {
        val explicit = variable.trim()
        if (explicit.isNotEmpty()) return explicit
        return deriveVariableName(source)
    }

    fun deriveVariableName(source: String): String {
        val text = source.trim()
        if (text.isEmpty()) return ""
        val leaf = try {
            val path = JsonPath.parse(text)
            when (path.root) {
                PathRoot.HEADERS -> path.headerName.orEmpty()
                PathRoot.DATA -> path.steps.lastOrNull()?.let { step ->
                    when (step) {
                        is PathStep.Member -> step.name
                        is PathStep.Index -> "index_${step.index}"
                    }
                }.orEmpty()

                PathRoot.STATUS -> "status"
                PathRoot.STATUS_TEXT -> "statusText"
            }
        } catch (e: PathSyntaxException) {
            text.substringAfterLast('.').substringAfterLast('[').substringBefore(']')
        }
        return sanitizeVariableName(leaf)
    }

    /** Variable names are usable in `{{...}}`, so only word characters survive. */
    fun sanitizeVariableName(raw: String): String {
        val builder = StringBuilder()
        raw.trim().forEach { c ->
            builder.append(if (c.isLetterOrDigit() || c == '_') c else '_')
        }
        var name = builder.toString()
        while (name.contains("__")) name = name.replace("__", "_")
        name = name.trim('_')
        if (name.isEmpty()) return ""
        if (name.first().isDigit()) name = "_$name"
        return name
    }
}
