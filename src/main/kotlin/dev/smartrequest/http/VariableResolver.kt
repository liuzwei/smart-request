package dev.smartrequest.http

import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue

data class VariableResolution(
    val text: String,
    val unresolved: Set<String>
) {
    val hasUnresolved: Boolean get() = unresolved.isNotEmpty()
}

/**
 * Resolves `{{variables}}` recursively. Values may reference other variables; unknown variables are left
 * untouched and reported so the UI can explain exactly what is missing.
 */
object VariableResolver {
    private val VARIABLE_PATTERN = Regex("""\{\{\s*([^{}\s]+?)\s*}}""")
    private const val MAX_DEPTH = 10

    fun environmentVariables(environment: EnvironmentConfig?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        environment?.baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { baseUrl ->
            result["baseUrl"] = baseUrl.trimEnd('/')
        }
        environment?.variables
            ?.filter { it.enabled && it.key.isNotBlank() }
            ?.forEach { result[it.key.trim()] = it.value }
        return result
    }

    fun resolve(input: String, variables: Map<String, String>): VariableResolution {
        var current = input
        repeat(MAX_DEPTH) {
            var changed = false
            current = VARIABLE_PATTERN.replace(current) { match ->
                val name = match.groupValues[1]
                val value = variables[name]
                if (value != null) {
                    changed = true
                    value
                } else {
                    match.value
                }
            }
            if (!changed) {
                return VariableResolution(current, unresolvedNames(current))
            }
        }
        return VariableResolution(current, unresolvedNames(current))
    }

    fun resolveValues(values: Map<String, String>, variables: Map<String, String>): Pair<Map<String, String>, Set<String>> {
        val unresolved = LinkedHashSet<String>()
        val resolved = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            val result = resolve(value, variables)
            resolved[key] = result.text
            unresolved += result.unresolved
        }
        return resolved to unresolved
    }

    fun unresolvedNames(input: String): Set<String> =
        VARIABLE_PATTERN.findAll(input).map { it.groupValues[1] }.toCollection(LinkedHashSet())

    fun mergeVariables(vararg maps: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        maps.forEach { merged.putAll(it) }
        return merged
    }

    /**
     * Converts a list of enabled key/value pairs to a map. Later values win, matching the UI ordering.
     */
    fun toVariableMap(values: List<KeyValue>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        values.filter { it.enabled && it.key.isNotBlank() }.forEach {
            result[it.key.trim()] = it.value
        }
        return result
    }
}
