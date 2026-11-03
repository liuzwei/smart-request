package dev.smartrequest.util

/**
 * Small dependency-free JSON pretty printer used only for display. It intentionally preserves the original
 * scalar tokens and falls back to the raw body when the input is not valid JSON.
 */
object JsonPrettyPrinter {
    private const val INDENT = "  "

    fun prettyPrintOrNull(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || (trimmed.first() != '{' && trimmed.first() != '[')) return null
        if (!isValidJsonLike(trimmed)) return null

        val out = StringBuilder(trimmed.length + 256)
        var depth = 0
        var inString = false
        var escaped = false
        var index = 0

        while (index < trimmed.length) {
            val c = trimmed[index]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                index++
                continue
            }

            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                }
                '{', '[' -> {
                    out.append(c)
                    depth++
                    if (nextNonWhitespace(trimmed, index + 1) !in listOf('}', ']', null)) {
                        newLine(out, depth)
                    }
                }
                '}', ']' -> {
                    depth--
                    if (lastNonWhitespace(out) !in listOf('{', '[')) {
                        newLine(out, depth)
                    }
                    out.append(c)
                }
                ',' -> {
                    out.append(c)
                    newLine(out, depth)
                }
                ':' -> {
                    out.append(": ")
                }
                ' ', '\t', '\r', '\n' -> Unit
                else -> out.append(c)
            }
            index++
        }
        return out.toString()
    }

    private fun isValidJsonLike(input: String): Boolean {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var completed = false
        for (c in input) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            if (completed) {
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeLast() != c) return false
                    if (stack.isEmpty()) completed = true
                }
            }
        }
        return !inString && stack.isEmpty()
    }

    private fun nextNonWhitespace(value: String, start: Int): Char? {
        var index = start
        while (index < value.length) {
            val c = value[index]
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return c
            index++
        }
        return null
    }

    private fun lastNonWhitespace(value: StringBuilder): Char? {
        var index = value.length - 1
        while (index >= 0) {
            val c = value[index]
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return c
            index--
        }
        return null
    }

    private fun newLine(out: StringBuilder, depth: Int) {
        out.append('\n')
        repeat(depth) { out.append(INDENT) }
    }
}
