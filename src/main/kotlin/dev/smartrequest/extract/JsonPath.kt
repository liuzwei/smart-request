package dev.smartrequest.extract

/**
 * A minimal JSON reader used by the Extract feature.
 *
 * The plugin ships no JSON library: responses are already in memory as text, and a small reader
 * keeps the plugin free of a runtime dependency while giving the UI precise error messages.
 * Numbers keep their original token text, so a 19 digit id is never reformatted through a double.
 *
 * This is display/extraction code, not a validator: it accepts exactly the JSON it can evaluate.
 */
internal sealed interface JsonNode {
    class Obj(val entries: LinkedHashMap<String, JsonNode>) : JsonNode
    class Arr(val items: List<JsonNode>) : JsonNode
    class Str(val value: String) : JsonNode
    class Num(val raw: String) : JsonNode
    class Bool(val value: Boolean) : JsonNode
    object Null : JsonNode
}

internal object JsonReader {
    private const val MAX_DEPTH = 256

    /** Returns null when [text] is not a single complete JSON value. */
    fun parse(text: String): JsonNode? = Parser(text).parseDocument()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonNode? {
            val value = parseValue(0) ?: return null
            skipWhitespace()
            return if (index == text.length) value else null
        }

        private fun parseValue(depth: Int): JsonNode? {
            if (depth > MAX_DEPTH) return null
            skipWhitespace()
            if (index >= text.length) return null
            return when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()?.let { JsonNode.Str(it) }
                't' -> if (consumeLiteral("true")) JsonNode.Bool(true) else null
                'f' -> if (consumeLiteral("false")) JsonNode.Bool(false) else null
                'n' -> if (consumeLiteral("null")) JsonNode.Null else null
                '-', in '0'..'9' -> parseNumber()
                else -> null
            }
        }

        private fun parseObject(depth: Int): JsonNode? {
            index++ // '{'
            val entries = LinkedHashMap<String, JsonNode>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonNode.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') return null
                val key = parseString() ?: return null
                skipWhitespace()
                if (peek() != ':') return null
                index++
                val value = parseValue(depth + 1) ?: return null
                entries[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonNode.Obj(entries)
                    }
                    else -> return null
                }
            }
        }

        private fun parseArray(depth: Int): JsonNode? {
            index++ // '['
            val items = ArrayList<JsonNode>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonNode.Arr(items)
            }
            while (true) {
                val value = parseValue(depth + 1) ?: return null
                items += value
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonNode.Arr(items)
                    }
                    else -> return null
                }
            }
        }

        private fun parseString(): String? {
            if (peek() != '"') return null
            index++
            val builder = StringBuilder()
            while (index < text.length) {
                when (val c = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) return null
                        when (val escaped = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) return null
                                val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                                index += 4
                                builder.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    else -> builder.append(c)
                }
            }
            return null
        }

        private fun parseNumber(): JsonNode? {
            val start = index
            if (peek() == '-') index++
            if (!skipDigits()) return null
            if (peek() == '.') {
                index++
                if (!skipDigits()) return null
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (!skipDigits()) return null
            }
            return JsonNode.Num(text.substring(start, index))
        }

        private fun skipDigits(): Boolean {
            val start = index
            while (index < text.length && text[index].isDigit()) index++
            return index > start
        }

        private fun consumeLiteral(literal: String): Boolean {
            if (!text.startsWith(literal, index)) return false
            index += literal.length
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null
    }
}

/** Renders a parsed node back to text: scalars as their value, objects and arrays as compact JSON. */
internal object JsonText {

    fun scalarOrNull(node: JsonNode): String? = when (node) {
        is JsonNode.Str -> node.value
        is JsonNode.Num -> node.raw
        is JsonNode.Bool -> node.value.toString()
        else -> null
    }

    fun compact(node: JsonNode): String = StringBuilder().also { write(node, it) }.toString()

    private fun write(node: JsonNode, out: StringBuilder) {
        when (node) {
            is JsonNode.Obj -> {
                out.append('{')
                var first = true
                node.entries.forEach { (key, value) ->
                    if (!first) out.append(',')
                    first = false
                    writeQuoted(key, out)
                    out.append(':')
                    write(value, out)
                }
                out.append('}')
            }

            is JsonNode.Arr -> {
                out.append('[')
                node.items.forEachIndexed { position, item ->
                    if (position > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }

            is JsonNode.Str -> writeQuoted(node.value, out)
            is JsonNode.Num -> out.append(node.raw)
            is JsonNode.Bool -> out.append(node.value)
            JsonNode.Null -> out.append("null")
        }
    }

    private fun writeQuoted(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format(java.util.Locale.US, "%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

internal enum class PathRoot { DATA, HEADERS, STATUS, STATUS_TEXT }

internal sealed interface PathStep {
    data class Member(val name: String) : PathStep
    data class Index(val index: Int) : PathStep
}

internal class ParsedPath(
    val root: PathRoot,
    val steps: List<PathStep>,
    val headerName: String? = null
)

internal class PathSyntaxException(message: String) : Exception(message)

internal sealed interface Evaluation {
    data class Found(val value: String) : Evaluation
    data class Failure(val message: String) : Evaluation
}

/**
 * Parses and evaluates the path syntax used by the Extract tab.
 *
 * Supported forms:
 * - `data`, `$`, `body` – the parsed JSON body
 * - `data.user.id`, `data.items[0].id`, `data.items[-1].id`, `data["user.name"]`
 * - `headers.X-Request-Id`, `header.Content-Type`
 * - `status`, `statusCode`, `statusText`
 */
internal object JsonPath {
    private val ROOT_PATTERN = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*""")
    private val PLAIN_KEY = Regex("""^[A-Za-z0-9_\-]+$""")

    fun parse(source: String): ParsedPath {
        val text = source.trim()
        if (text.isEmpty()) throw PathSyntaxException("Expression is empty")

        val match = ROOT_PATTERN.find(text)
            ?: throw PathSyntaxException("Start with data, headers, status or statusText")
        val rootToken = match.value
        var index = rootToken.length

        return when (rootToken) {
            "$", "data", "body" -> ParsedPath(
                PathRoot.DATA,
                parseSteps(text, index)
            )

            "headers", "header" -> {
                if (index >= text.length || text[index] != '.') {
                    throw PathSyntaxException("Add a header name, for example headers.X-Request-Id")
                }
                val name = text.substring(index + 1).trim()
                if (name.isEmpty()) {
                    throw PathSyntaxException("Add a header name, for example headers.X-Request-Id")
                }
                if (name.any { it.isWhitespace() }) {
                    throw PathSyntaxException("Header name must not contain spaces")
                }
                ParsedPath(PathRoot.HEADERS, emptyList(), name)
            }

            "status", "statusCode" -> {
                if (text.length > index) throw PathSyntaxException("status does not take a path")
                ParsedPath(PathRoot.STATUS, emptyList())
            }

            "statusText" -> {
                if (text.length > index) throw PathSyntaxException("statusText does not take a path")
                ParsedPath(PathRoot.STATUS_TEXT, emptyList())
            }

            else -> throw PathSyntaxException(
                "Unknown source '$rootToken': use data, headers, status or statusText"
            )
        }
    }

    private fun parseSteps(text: String, from: Int): List<PathStep> {
        val steps = ArrayList<PathStep>()
        var index = from
        while (index < text.length) {
            when (text[index]) {
                '.' -> {
                    val start = index + 1
                    index = start
                    while (index < text.length && text[index] != '.' && text[index] != '[' && !text[index].isWhitespace()) {
                        index++
                    }
                    if (index == start) {
                        throw PathSyntaxException("Missing a key after '.' at position ${start + 1}")
                    }
                    steps += PathStep.Member(text.substring(start, index))
                }

                '[' -> {
                    val parsed = readBracket(text, index)
                    steps += parsed.step
                    index = parsed.closeIndex + 1
                }

                else -> throw PathSyntaxException(
                    "Unexpected character '${text[index]}' at position ${index + 1}"
                )
            }
        }
        return steps
    }

    private class BracketResult(val step: PathStep, val closeIndex: Int)

    private fun readBracket(text: String, openIndex: Int): BracketResult {
        var index = openIndex + 1
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length) throw PathSyntaxException("Missing ']'")

        val first = text[index]
        if (first == '"' || first == '\'') {
            index++
            val builder = StringBuilder()
            var closed = false
            while (index < text.length) {
                val c = text[index]
                if (c == '\\' && index + 1 < text.length) {
                    builder.append(text[index + 1])
                    index += 2
                    continue
                }
                if (c == first) {
                    index++
                    closed = true
                    break
                }
                builder.append(c)
                index++
            }
            if (!closed) throw PathSyntaxException("Missing the closing quote in [...]")
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length || text[index] != ']') throw PathSyntaxException("Missing ']'")
            return BracketResult(PathStep.Member(builder.toString()), index)
        }

        val start = index
        while (index < text.length && text[index] != ']') index++
        if (index >= text.length) throw PathSyntaxException("Missing ']'")
        val raw = text.substring(start, index).trim()
        val number = raw.toIntOrNull()
            ?: throw PathSyntaxException("Use [0], [-1] or [\"key\"] instead of [$raw]")
        return BracketResult(PathStep.Index(number), index)
    }

    fun evaluate(parsed: ParsedPath, root: JsonNode): Evaluation {
        if (parsed.root != PathRoot.DATA) {
            return Evaluation.Failure("Unsupported source")
        }
        var current: JsonNode = root
        var display = "data"
        for (step in parsed.steps) {
            when (step) {
                is PathStep.Member -> {
                    val obj = current as? JsonNode.Obj
                        ?: return Evaluation.Failure("'$display' is not an object")
                    current = obj.entries[step.name]
                        ?: return Evaluation.Failure("key '${step.name}' is not present in '$display'")
                    display = appendMember(display, step.name)
                }

                is PathStep.Index -> {
                    val array = current as? JsonNode.Arr
                        ?: return Evaluation.Failure("'$display' is not an array")
                    val position = if (step.index < 0) array.items.size + step.index else step.index
                    current = array.items.getOrNull(position)
                        ?: return Evaluation.Failure(
                            "index ${step.index} is out of bounds for '$display' (size ${array.items.size})"
                        )
                    display = "$display[${step.index}]"
                }
            }
        }
        if (current is JsonNode.Null) {
            return Evaluation.Failure("value at '$display' is null")
        }
        return Evaluation.Found(JsonText.scalarOrNull(current) ?: JsonText.compact(current))
    }

    private fun appendMember(display: String, name: String): String =
        if (PLAIN_KEY.matches(name)) "$display.$name" else "$display[\"$name\"]"
}
