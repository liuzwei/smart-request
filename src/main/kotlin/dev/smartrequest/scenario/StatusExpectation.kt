package dev.smartrequest.scenario

/**
 * Parses the per-node expected status expression. An empty expression keeps the HTTP-client default
 * of "anything below 400 is fine"; everything else is explicit so an error path can be tested:
 *
 * - `404` – exactly 404
 * - `2xx` – a whole status class
 * - `200-299` – an inclusive range
 * - `200,201,204` – any of the listed codes
 */
object StatusExpectation {

    fun error(expression: String): String? {
        if (expression.isBlank()) return null
        if (parse(expression) != null) return null
        return "expect status '$expression' is not valid; use 200, 2xx, 200-299 or a comma separated list"
    }

    fun matches(expression: String, statusCode: Int): Boolean {
        val ranges = parse(expression) ?: return false
        return ranges.any { statusCode in it }
    }

    fun describe(expression: String): String = if (expression.isBlank()) "200-399" else expression.trim()

    private fun parse(expression: String): List<IntRange>? {
        val text = expression.trim()
        if (text.isEmpty()) return listOf(200..399)
        val ranges = ArrayList<IntRange>()
        text.split(',').forEach { rawToken ->
            val token = rawToken.trim()
            if (token.isEmpty()) return null
            ranges.add(parseToken(token) ?: return null)
        }
        return ranges
    }

    private fun parseToken(token: String): IntRange? {
        val lower = token.lowercase()
        if (lower.length == 3 && lower[1] == 'x' && lower[2] == 'x' && lower[0] in '1'..'5') {
            val start = (lower[0] - '0') * 100
            return start..(start + 99)
        }
        if (token.length == 3 && token.all { it.isDigit() }) {
            val code = token.toInt()
            return if (code in 100..599) code..code else null
        }
        val parts = token.split('-')
        if (parts.size == 2 && parts.all { part -> part.length == 3 && part.all { it.isDigit() } }) {
            val from = parts[0].toInt()
            val to = parts[1].toInt()
            return if (from in 100..599 && to in 100..599 && from <= to) from..to else null
        }
        return null
    }
}
