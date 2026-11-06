package dev.smartrequest.template

import dev.smartrequest.model.FunctionDefinition

/**
 * A user-defined pure macro. The expression can reference the macro's parameters, string literals
 * and other functions; it cannot read environment variables or project state. Macro calls are
 * depth-limited to catch accidental recursion.
 */
class MacroFunction(
    private val definition: FunctionDefinition,
    private val registryProvider: () -> FunctionRegistry
) : TemplateFunction {
    override val name: String = definition.name
    override val minArgs: Int = definition.parameters.size
    override val maxArgs: Int = definition.parameters.size

    override fun invoke(args: List<String>): String {
        if (args.size != definition.parameters.size) {
            throw TemplateFunctionException(
                "Macro '${definition.name}' expects ${definition.parameters.size} argument(s), " +
                    "got ${args.size}"
            )
        }
        val current = DEPTH.get()
        if (current >= MAX_MACRO_DEPTH) {
            throw TemplateFunctionException(
                "Macro '${definition.name}' exceeded the maximum call depth ($MAX_MACRO_DEPTH)"
            )
        }
        DEPTH.set(current + 1)
        try {
            val variables = LinkedHashMap<String, String>()
            definition.parameters.zip(args).forEach { (parameter, value) ->
                variables[parameter] = value
            }
            val resolution = TemplateEngine.resolve(
                TemplateEngine.expressionTemplate(definition.expression),
                variables,
                registryProvider()
            )
            if (resolution.errors.isNotEmpty()) {
                throw TemplateFunctionException(resolution.errors.joinToString("; "))
            }
            if (resolution.unresolved.isNotEmpty()) {
                throw TemplateFunctionException(
                    "Macro '${definition.name}' has unknown parameter(s): " +
                        resolution.unresolved.joinToString(", ")
                )
            }
            return resolution.text
        } finally {
            DEPTH.set(current)
        }
    }

    companion object {
        private const val MAX_MACRO_DEPTH = 20
        private val DEPTH = ThreadLocal.withInitial { 0 }
    }
}
