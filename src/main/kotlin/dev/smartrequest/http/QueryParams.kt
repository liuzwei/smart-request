package dev.smartrequest.http

import dev.smartrequest.model.KeyValue
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Two-way conversion between the query string of a URL and the parameter table of the request
 * editor.
 *
 * Values stay readable in the table: percent escapes are decoded when the URL is read and applied
 * again when the URL is rebuilt. `{{variable}}` placeholders are never encoded, so variable
 * highlighting keeps working while the value is typed.
 */
object QueryParams {

    private val VARIABLE = Regex("""\{\{[^{}\s]+}}""")

    /** Query pairs of [url] in URL order, decoded. A missing `=` means an empty value. */
    fun parse(url: String): List<Pair<String, String>> {
        val query = rawQuery(url)
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) {
                decode(pair) to ""
            } else {
                decode(pair.substring(0, separator)) to decode(pair.substring(separator + 1))
            }
        }
    }

    /** Replaces the query of [url] with [params]; an empty list removes the query. */
    fun replaceQuery(url: String, params: List<Pair<String, String>>): String {
        val (withoutFragment, fragment) = stripFragment(url)
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex < 0) withoutFragment else withoutFragment.substring(0, queryIndex)
        val query = params.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return if (query.isEmpty()) base + fragment else "$base?$query$fragment"
    }

    /**
     * Rebuilds the parameter rows from [url].
     *
     * Existing rows are reused by key so the enabled flag and object identity survive an edit. Rows
     * that the URL does not mention are kept when they are disabled (they are intentionally left out
     * of the URL) or when [keepUnknown] is set, which is used when opening a request whose
     * parameters were saved before the URL and the table were kept in sync.
     */
    fun rowsFromUrl(url: String, existing: List<KeyValue>, keepUnknown: Boolean = false): List<KeyValue> {
        val remaining = existing.toMutableList()
        val rows = ArrayList<KeyValue>()
        parse(url).forEach { (key, value) ->
            val match = remaining.firstOrNull { it.key == key }
            if (match == null) {
                rows += KeyValue.of(key, value)
            } else {
                remaining.remove(match)
                match.value = value
                match.enabled = true
                rows += match
            }
        }
        remaining.forEach { if (keepUnknown || !it.enabled) rows += it }
        return rows
    }

    /** The pairs that belong in the URL for [rows]. */
    fun pairsForUrl(rows: List<KeyValue>): List<Pair<String, String>> =
        rows.filter { it.enabled && it.key.isNotBlank() }.map { it.key to it.value }

    /** The raw query of [url], without the leading `?` and without the fragment. */
    fun rawQuery(url: String): String {
        val withoutFragment = stripFragment(url).first
        val queryIndex = withoutFragment.indexOf('?')
        return if (queryIndex < 0) "" else withoutFragment.substring(queryIndex + 1)
    }

    private fun stripFragment(url: String): Pair<String, String> {
        val index = url.indexOf('#')
        return if (index < 0) url to "" else url.substring(0, index) to url.substring(index)
    }

    private fun encode(text: String): String {
        if (text.isEmpty()) return ""
        val result = StringBuilder()
        var last = 0
        VARIABLE.findAll(text).forEach { match ->
            result.append(encodeLiteral(text.substring(last, match.range.first)))
            result.append(match.value)
            last = match.range.last + 1
        }
        result.append(encodeLiteral(text.substring(last)))
        return result.toString()
    }

    private fun encodeLiteral(text: String): String =
        if (text.isEmpty()) "" else URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")

    private fun decode(text: String): String {
        if (!text.contains('%') && !text.contains('+')) return text
        return runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrDefault(text)
    }
}
