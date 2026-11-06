package dev.smartrequest.http

import dev.smartrequest.model.KeyValue
import dev.smartrequest.template.FunctionRegistry
import dev.smartrequest.template.TemplateEngine
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RequestBuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class UrlBuildResult(
    val url: String,
    val unresolved: Set<String>,
    val errors: List<String> = emptyList()
)

/**
 * Builds the final request URL.
 *
 * The request parameters own the query string: the editor keeps the URL text and the parameter table
 * in sync, so whenever parameters are supplied they replace the query already present in the URL.
 * A URL whose query is not described by any parameter (for example when the model is used directly)
 * keeps it untouched.
 */
object RequestUrlBuilder {

    fun build(
        rawUrl: String,
        parameters: List<Pair<String, String>>,
        variables: Map<String, String> = emptyMap(),
        pathParams: List<KeyValue> = emptyList(),
        registry: FunctionRegistry = FunctionRegistry.builtIns()
    ): UrlBuildResult {
        val unresolved = LinkedHashSet<String>()
        val errors = ArrayList<String>()
        val resolvedUrl = TemplateEngine.resolve(rawUrl, variables, registry)
        unresolved += resolvedUrl.unresolved
        errors += resolvedUrl.errors
        val resolvedPath = PathParameterResolver.resolve(resolvedUrl.text, pathParams, variables, registry)
        unresolved += resolvedPath.unresolved
        errors += resolvedPath.errors
        var url = resolvedPath.url.trim()

        if (url.isEmpty()) {
            throw RequestBuildException("URL is empty")
        }

        if (!hasScheme(url)) {
            if (url.startsWith("/")) {
                throw RequestBuildException("URL must be absolute or start with a variable such as {{baseUrl}}")
            }
            url = "http://$url"
        }

        val additions = parameters.map { (key, value) ->
            val resolvedKey = TemplateEngine.resolve(key, variables, registry)
            val resolvedValue = TemplateEngine.resolve(value, variables, registry)
            unresolved += resolvedKey.unresolved
            unresolved += resolvedValue.unresolved
            errors += resolvedKey.errors
            errors += resolvedValue.errors
            "${encodeQueryComponent(resolvedKey.text)}=${encodeQueryComponent(resolvedValue.text)}"
        }

        if (additions.isNotEmpty()) {
            url = replaceQuery(url, additions)
        }

        if (unresolved.isNotEmpty() || errors.isNotEmpty()) {
            return UrlBuildResult(url, unresolved, errors.distinct())
        }
        validate(url)
        return UrlBuildResult(url, unresolved, emptyList())
    }

    private fun hasScheme(url: String): Boolean =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)

    private fun replaceQuery(url: String, additions: List<String>): String {
        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url

        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment

        return "$base?${additions.joinToString("&")}$fragment"
    }

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun validate(url: String) {
        val uri: URI = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw RequestBuildException("Invalid URL: ${e.reason}", e)
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw RequestBuildException("Only http:// and https:// URLs are supported")
        }
        if (uri.host.isNullOrBlank()) {
            throw RequestBuildException("URL has no host: $url")
        }
        if (uri.rawUserInfo != null) {
            throw RequestBuildException("User info in URLs is not supported; use an Authorization header instead")
        }
    }
}
