package dev.smartrequest.template

import java.util.Locale

sealed interface TemplateExpr {
    data class Literal(val value: String) : TemplateExpr

    data class Variable(
        val name: String,
        val start: Int,
        val end: Int
    ) : TemplateExpr

    data class Call(
        val name: String,
        val args: List<TemplateExpr>,
        val start: Int,
        val end: Int
    ) : TemplateExpr
}

data class TemplateSegment(
    val literal: String? = null,
    val expression: TemplateExpr? = null,
    val start: Int,
    val end: Int
)

data class TemplateIssue(
    val start: Int,
    val end: Int,
    val message: String,
    val kind: TemplateIssueKind
)

enum class TemplateIssueKind {
    SYNTAX,
    UNKNOWN_FUNCTION,
    ARGUMENTS
}

data class ParsedTemplate(
    val segments: List<TemplateSegment>,
    val issues: List<TemplateIssue>
)

data class TemplateReference(
    val name: String,
    val start: Int,
    val end: Int
)

data class TemplateAnalysis(
    val variables: Set<String>,
    val references: List<TemplateReference>,
    val functions: Set<String>,
    val issues: List<TemplateIssue>
)

data class TemplateResolution(
    val text: String,
    val unresolved: Set<String>,
    val errors: List<String>
) {
    val isSuccess: Boolean
        get() = unresolved.isEmpty() && errors.isEmpty()
}

/**
 * Parses `{{expression}}` templates. The supported grammar is deliberately small:
 *
 * ```
 * template    := (literal | "{{" expression "}}")*
 * expression  := string | number | variable | function
 * function    := name "(" [ expression ("," expression)* ] ")"
 * string      := "'" escaped "'" | "\"" escaped "\""
 * variable    := any token that is not followed by "("
 * ```
 *
 * A bare name is a variable. A name immediately followed by `(` is a function call. Functions are
 * looked up case-insensitively; variables are case-sensitive. String literals accept `\\`, `\'`,
 * `\"`, `\n`, `\r`, `\t`, `\b`, `\f` and `\uXXXX`.
 */
object TemplateParser {

    fun parse(template: String): ParsedTemplate {
        val segments = ArrayList<TemplateSegment>()
        val issues = ArrayList<TemplateIssue>()
        var cursor = 0

        while (cursor < template.length) {
            val open = template.indexOf("{{", cursor)
            if (open < 0) {
                if (cursor < template.length) {
                    segments += TemplateSegment(
                        literal = template.substring(cursor),
                        start = cursor,
                        end = template.length
                    )
                }
                break
            }
            if (open > cursor) {
                segments += TemplateSegment(
                    literal = template.substring(cursor, open),
                    start = cursor,
                    end = open
                )
            }

            val close = findExpressionEnd(template, open + 2)
            if (close < 0) {
                val message = "Unclosed template expression"
                issues += TemplateIssue(open, template.length, message, TemplateIssueKind.SYNTAX)
                segments += TemplateSegment(
                    literal = template.substring(open),
                    start = open,
                    end = template.length
                )
                break
            }

            val parser = ExpressionParser(template, open + 2, close)
            val expression = parser.parse()
            if (parser.issues.isNotEmpty()) {
                issues += parser.issues
            }
            if (expression == null) {
                segments += TemplateSegment(
                    literal = template.substring(open, close + 2),
                    start = open,
                    end = close + 2
                )
            } else {
                segments += TemplateSegment(
                    expression = expression,
                    start = open,
                    end = close + 2
                )
            }
            cursor = close + 2
        }

        return ParsedTemplate(segments, issues)
    }

    /**
     * Finds the closing `}}` while ignoring braces inside quoted string arguments. A quoted string
     * may contain `}}`, and an escaped quote must not end the string.
     */
    private fun findExpressionEnd(template: String, from: Int): Int {
        var index = from
        var quote: Char? = null
        var escaped = false
        while (index < template.length - 1) {
            val character = template[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else {
                    when (character) {
                        '\\' -> escaped = true
                        quote -> quote = null
                    }
                }
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '}' -> if (template[index + 1] == '}') return index
                }
            }
            index++
        }
        return -1
    }

    private class ExpressionParser(
        private val text: String,
        private val start: Int,
        private val end: Int
    ) {
        private var position = start
        val issues = ArrayList<TemplateIssue>()

        fun parse(): TemplateExpr? {
            skipWhitespace()
            if (position >= end) {
                issue("Empty expression", start, end)
                return null
            }
            val expression = parseExpression() ?: return null
            skipWhitespace()
            if (position != end) {
                issue("Unexpected text '${text.substring(position, end)}'", position, end)
                return null
            }
            return expression
        }

        private fun parseExpression(): TemplateExpr? {
            skipWhitespace()
            if (position >= end) {
                issue("Expected a variable, function or string", position, end)
                return null
            }
            return when (text[position]) {
                '\'', '"' -> parseString()
                else -> parseNameOrCall()
            }
        }

        private fun parseString(): TemplateExpr? {
            val quote = text[position]
            val stringStart = position
            position++
            val value = StringBuilder()
            while (position < end) {
                val character = text[position++]
                if (character == quote) {
                    return TemplateExpr.Literal(value.toString())
                }
                if (character != '\\') {
                    value.append(character)
                    continue
                }
                if (position >= end) {
                    issue("Unterminated escape sequence", stringStart, end)
                    return null
                }
                when (val escaped = text[position++]) {
                    '\\' -> value.append('\\')
                    '\'' -> value.append('\'')
                    '"' -> value.append('"')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000C')
                    'u' -> {
                        if (position + 4 > end) {
                            issue("Invalid unicode escape", position - 2, end)
                            return null
                        }
                        val hex = text.substring(position, position + 4)
                        val code = hex.toIntOrNull(16)
                        if (code == null) {
                            issue("Invalid unicode escape '\\u$hex'", position - 2, position + 4)
                            return null
                        }
                        value.append(code.toChar())
                        position += 4
                    }
                    else -> value.append(escaped)
                }
            }
            issue("Unterminated string", stringStart, end)
            return null
        }

        private fun parseNameOrCall(): TemplateExpr? {
            val tokenStart = position
            while (position < end) {
                val character = text[position]
                if (character.isWhitespace() || character in "(),'\"{}") break
                position++
            }
            if (position == tokenStart) {
                issue("Expected a variable or function name", position, end)
                return null
            }
            val name = text.substring(tokenStart, position)
            val nameEnd = position
            skipWhitespace()
            if (position < end && text[position] == '(') {
                position++
                val args = ArrayList<TemplateExpr>()
                skipWhitespace()
                if (position < end && text[position] == ')') {
                    position++
                    return TemplateExpr.Call(name, args, tokenStart, position)
                }
                while (true) {
                    val argument = parseExpression() ?: return null
                    args += argument
                    skipWhitespace()
                    if (position >= end) {
                        issue("Expected ')'", tokenStart, end)
                        return null
                    }
                    when (text[position]) {
                        ',' -> {
                            position++
                            skipWhitespace()
                            if (position < end && text[position] == ')') {
                                issue("Expected an argument after ','", position, end)
                                return null
                            }
                        }
                        ')' -> {
                            position++
                            return TemplateExpr.Call(name, args, tokenStart, position)
                        }
                        else -> {
                            issue("Expected ',' or ')'", position, end)
                            return null
                        }
                    }
                }
            }
            return if (name.matches(NUMBER)) {
                TemplateExpr.Literal(name)
            } else {
                TemplateExpr.Variable(name, tokenStart, nameEnd)
            }
        }

        private fun skipWhitespace() {
            while (position < end && text[position].isWhitespace()) position++
        }

        private fun issue(message: String, issueStart: Int, issueEnd: Int) {
            issues += TemplateIssue(issueStart, issueEnd, message, TemplateIssueKind.SYNTAX)
        }
    }

    private val NUMBER = Regex("""-?\d+(\.\d+)?""")
}

object TemplateEngine {
    const val MAX_DEPTH = 20
    const val MAX_OUTPUT_LENGTH = 1_000_000

    /**
     * A macro stores a bare expression such as `concat('Bearer ', token)`. Wrapping it in `{{...}}`
     * lets the same parser, analyzer and evaluator handle both request templates and macros.
     */
    fun expressionTemplate(expression: String): String {
        val trimmed = expression.trim()
        return if (trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            trimmed
        } else {
            "{{$trimmed}}"
        }
    }

    /**
     * Parses and statically analyzes a template. Used by validation and the editor highlighter.
     * It never evaluates functions; it only checks syntax, names, arity and collects variable
     * references (which are the dependencies used by the scenario data-flow validator).
     */
    fun analyze(
        template: String,
        registry: FunctionRegistry = FunctionRegistry.builtIns(),
        allowUnknownFunctions: Boolean = false
    ): TemplateAnalysis {
        val parsed = TemplateParser.parse(template)
        val references = ArrayList<TemplateReference>()
        val variables = LinkedHashSet<String>()
        val functions = LinkedHashSet<String>()
        val issues = ArrayList<TemplateIssue>(parsed.issues)

        fun visit(expression: TemplateExpr) {
            when (expression) {
                is TemplateExpr.Literal -> Unit
                is TemplateExpr.Variable -> {
                    references += TemplateReference(expression.name, expression.start, expression.end)
                    variables += expression.name
                }
                is TemplateExpr.Call -> {
                    val normalized = expression.name.lowercase(Locale.ROOT)
                    functions += normalized
                    val function = registry.find(expression.name)
                    if (function == null) {
                        if (!allowUnknownFunctions) {
                            issues += TemplateIssue(
                                expression.start,
                                expression.end,
                                "Unknown function '${expression.name}'",
                                TemplateIssueKind.UNKNOWN_FUNCTION
                            )
                        }
                    } else if (expression.args.size !in function.minArgs..function.maxArgs) {
                        issues += TemplateIssue(
                            expression.start,
                            expression.end,
                            arityMessage(function, expression.args.size),
                            TemplateIssueKind.ARGUMENTS
                        )
                    }
                    expression.args.forEach(::visit)
                }
            }
        }

        parsed.segments.forEach { segment ->
            segment.expression?.let(::visit)
        }

        return TemplateAnalysis(
            variables = variables,
            references = references,
            functions = functions,
            issues = issues
        )
    }

    fun referencedVariables(template: String): Set<String> = analyze(template).variables

    /**
     * Resolves a template for request preparation. Variable values are templates themselves and are
     * resolved recursively; function results are final text and are never rescanned.
     *
     * Unknown functions, syntax errors and function failures are returned as `errors` and the
     * caller must refuse to send the request. Missing variables are returned in `unresolved` so the
     * UI can show the usual "Unresolved variables: ..." message.
     */
    fun resolve(
        template: String,
        variables: Map<String, String>,
        registry: FunctionRegistry = FunctionRegistry.builtIns()
    ): TemplateResolution = resolveInternal(
        template = template,
        variables = variables,
        registry = registry,
        depth = 0,
        stack = emptySet()
    )

    private fun resolveInternal(
        template: String,
        variables: Map<String, String>,
        registry: FunctionRegistry,
        depth: Int,
        stack: Set<String>
    ): TemplateResolution {
        if (depth > MAX_DEPTH) {
            return TemplateResolution(
                template,
                emptySet(),
                listOf("Template nesting is too deep (max $MAX_DEPTH)")
            )
        }
        if (!template.contains("{{")) {
            return TemplateResolution(template, emptySet(), emptyList())
        }

        val parsed = TemplateParser.parse(template)
        if (parsed.issues.isNotEmpty()) {
            return TemplateResolution(
                template,
                emptySet(),
                parsed.issues.map { it.message }.distinct()
            )
        }

        val text = StringBuilder()
        val unresolved = LinkedHashSet<String>()
        val errors = LinkedHashSet<String>()

        for (segment in parsed.segments) {
            val literal = segment.literal
            if (literal != null) {
                text.append(literal)
                if (text.length > MAX_OUTPUT_LENGTH) {
                    errors += "Template output is too large (max $MAX_OUTPUT_LENGTH characters)"
                    break
                }
                continue
            }
            val expression = segment.expression ?: continue
            val result = evaluate(expression, template, variables, registry, depth, stack)
            text.append(result.text)
            unresolved += result.unresolved
            errors += result.errors
            if (text.length > MAX_OUTPUT_LENGTH) {
                errors += "Template output is too large (max $MAX_OUTPUT_LENGTH characters)"
                break
            }
        }

        return TemplateResolution(text.toString(), unresolved, errors.toList())
    }

    private data class Evaluation(
        val text: String,
        val unresolved: Set<String> = emptySet(),
        val errors: List<String> = emptyList()
    )

    private fun evaluate(
        expression: TemplateExpr,
        source: String,
        variables: Map<String, String>,
        registry: FunctionRegistry,
        depth: Int,
        stack: Set<String>
    ): Evaluation {
        return when (expression) {
            is TemplateExpr.Literal -> Evaluation(expression.value)
            is TemplateExpr.Variable -> evaluateVariable(
                expression,
                variables,
                registry,
                depth,
                stack
            )
            is TemplateExpr.Call -> evaluateCall(
                expression,
                source,
                variables,
                registry,
                depth,
                stack
            )
        }
    }

    private fun evaluateVariable(
        expression: TemplateExpr.Variable,
        variables: Map<String, String>,
        registry: FunctionRegistry,
        depth: Int,
        stack: Set<String>
    ): Evaluation {
        val value = variables[expression.name]
        if (value == null) {
            return Evaluation(
                text = "{{${expression.name}}}",
                unresolved = setOf(expression.name)
            )
        }
        if (expression.name in stack) {
            return Evaluation(
                text = "{{${expression.name}}}",
                errors = listOf("Circular variable reference: '${expression.name}'")
            )
        }
        if (depth >= MAX_DEPTH) {
            return Evaluation(
                text = "{{${expression.name}}}",
                errors = listOf("Template nesting is too deep (max $MAX_DEPTH)")
            )
        }
        val nested = resolveInternal(
            template = value,
            variables = variables,
            registry = registry,
            depth = depth + 1,
            stack = stack + expression.name
        )
        return Evaluation(nested.text, nested.unresolved, nested.errors)
    }

    private fun evaluateCall(
        expression: TemplateExpr.Call,
        source: String,
        variables: Map<String, String>,
        registry: FunctionRegistry,
        depth: Int,
        stack: Set<String>
    ): Evaluation {
        val raw = "{{" + source.substring(expression.start, expression.end) + "}}"
        val function = registry.find(expression.name)
            ?: return Evaluation(
                text = raw,
                errors = listOf("Unknown function '${expression.name}'")
            )
        if (expression.args.size !in function.minArgs..function.maxArgs) {
            return Evaluation(
                text = raw,
                errors = listOf(arityMessage(function, expression.args.size))
            )
        }

        val arguments = ArrayList<String>(expression.args.size)
        val unresolved = LinkedHashSet<String>()
        val errors = LinkedHashSet<String>()
        for (argument in expression.args) {
            val evaluated = evaluate(argument, source, variables, registry, depth, stack)
            arguments += evaluated.text
            unresolved += evaluated.unresolved
            errors += evaluated.errors
        }
        if (unresolved.isNotEmpty() || errors.isNotEmpty()) {
            return Evaluation(raw, unresolved, errors.toList())
        }

        return try {
            Evaluation(function.invoke(arguments))
        } catch (e: TemplateFunctionException) {
            Evaluation(
                text = raw,
                errors = listOf("Function '${expression.name}' failed: ${e.message ?: "unknown error"}")
            )
        } catch (e: Exception) {
            Evaluation(
                text = raw,
                errors = listOf(
                    "Function '${expression.name}' failed: ${e.message ?: e.javaClass.simpleName}"
                )
            )
        }
    }

    private fun arityMessage(function: TemplateFunction, actual: Int): String {
        val expected = when {
            function.minArgs == function.maxArgs -> "${function.minArgs} argument(s)"
            function.maxArgs == Int.MAX_VALUE -> "at least ${function.minArgs} argument(s)"
            else -> "${function.minArgs}..${function.maxArgs} arguments"
        }
        return "Function '${function.name}' expects $expected, got $actual"
    }
}
