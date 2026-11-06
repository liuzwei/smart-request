package dev.smartrequest.template

import java.util.Locale

/**
 * A pure, side-effect free function that can be called from a `{{...}}` template expression.
 *
 * Functions only receive strings and return a string. They must not read files, the network, the
 * environment or any mutable state: the same arguments always produce the same result. This keeps
 * request preparation deterministic and makes every function trivial to unit test.
 */
interface TemplateFunction {
    val name: String
    val minArgs: Int
    val maxArgs: Int

    fun invoke(args: List<String>): String
}

class TemplateFunctionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Immutable function table. Lookups are case-insensitive so `base64` and `BASE64` are the same
 * function; variable names remain case-sensitive.
 */
class FunctionRegistry private constructor(
    private val functions: Map<String, TemplateFunction>
) {
    fun find(name: String): TemplateFunction? = functions[name.lowercase(Locale.ROOT)]

    fun names(): Set<String> = functions.keys

    fun all(): Collection<TemplateFunction> = functions.values

    companion object {
        fun builtIns(): FunctionRegistry = FunctionRegistry(
            BuiltInFunctions.all.associateBy { it.name.lowercase(Locale.ROOT) }
        )

        fun of(functions: Collection<TemplateFunction>): FunctionRegistry = FunctionRegistry(
            functions.associateBy { it.name.lowercase(Locale.ROOT) }
        )
    }
}
