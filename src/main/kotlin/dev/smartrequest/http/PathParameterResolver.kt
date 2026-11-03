package dev.smartrequest.http

import dev.smartrequest.model.KeyValue
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Handles REST-style path variables: `/users/{id}`.
 *
 * Single braces are deliberately kept separate from `{{environmentVariables}}`. Path values are
 * resolved as environment variables too, then URL-encoded as one path segment.
 */
object PathParameterResolver {
    private val PLACEHOLDER = Regex("""(?<!\{)\{([A-Za-z_][A-Za-z0-9_.-]*)(?::[^{}]*)?}(?!})""")

    data class PathResolution(
        val url: String,
        val unresolved: Set<String>
    )

    fun detectNames(url: String): List<String> {
        val path = pathPart(url)
        return PLACEHOLDER.findAll(path)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    fun resolve(
        url: String,
        pathParams: List<KeyValue>,
        variables: Map<String, String>
    ): PathResolution {
        val pathEnd = firstSeparator(url)
        val path = url.substring(0, pathEnd)
        val suffix = url.substring(pathEnd)
        val values = LinkedHashMap<String, KeyValue>()
        pathParams.filter { it.key.isNotBlank() }.forEach { values.putIfAbsent(it.key.trim(), it) }
        val unresolved = LinkedHashSet<String>()

        val resolvedPath = PLACEHOLDER.replace(path) { match ->
            val name = match.groupValues[1]
            val parameter = values[name]
                ?: throw RequestBuildException("Path parameter '$name' has no value")
            val resolved = VariableResolver.resolve(parameter.value, variables)
            unresolved += resolved.unresolved
            if (resolved.text.isBlank()) {
                throw RequestBuildException("Path parameter '$name' is empty")
            }
            encodePathSegment(resolved.text)
        }

        return PathResolution(resolvedPath + suffix, unresolved)
    }

    fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun pathPart(url: String): String = url.substring(0, firstSeparator(url))

    private fun firstSeparator(url: String): Int {
        val query = url.indexOf('?').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val fragment = url.indexOf('#').takeIf { it >= 0 } ?: Int.MAX_VALUE
        return minOf(query, fragment, url.length)
    }
}
