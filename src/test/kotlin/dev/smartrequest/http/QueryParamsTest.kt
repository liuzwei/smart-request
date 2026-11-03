package dev.smartrequest.http

import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryParamsTest {

    @Test
    fun `parses query pairs in url order`() {
        val pairs = QueryParams.parse("{{baseUrl}}/users?page=2&size=10#top")

        assertEquals(listOf("page" to "2", "size" to "10"), pairs)
    }

    @Test
    fun `parses duplicate keys and keys without a value`() {
        val pairs = QueryParams.parse("http://localhost/api?tag=a&tag=b&flag")

        assertEquals(listOf("tag" to "a", "tag" to "b", "flag" to ""), pairs)
    }

    @Test
    fun `decodes percent escapes when reading the url`() {
        val pairs = QueryParams.parse("http://localhost/api?q=hello%20world&plus=a%2Bb")

        assertEquals(listOf("q" to "hello world", "plus" to "a+b"), pairs)
    }

    @Test
    fun `ignores a url without a query`() {
        assertTrue(QueryParams.parse("{{baseUrl}}/users").isEmpty())
        assertTrue(QueryParams.parse("{{baseUrl}}/users?").isEmpty())
    }

    @Test
    fun `builds a query and keeps the path and fragment`() {
        val url = QueryParams.replaceQuery(
            "{{baseUrl}}/users#top",
            listOf("page" to "2", "q" to "hello world")
        )

        assertEquals("{{baseUrl}}/users?page=2&q=hello%20world#top", url)
    }

    @Test
    fun `replaces an existing query instead of appending to it`() {
        val url = QueryParams.replaceQuery("http://localhost/api?old=1", listOf("new" to "2"))

        assertEquals("http://localhost/api?new=2", url)
    }

    @Test
    fun `empty rows remove the query`() {
        assertEquals("http://localhost/api#top", QueryParams.replaceQuery("http://localhost/api?a=1#top", emptyList()))
    }

    @Test
    fun `keeps variable placeholders readable in the url`() {
        val url = QueryParams.replaceQuery("{{baseUrl}}/api", listOf("tenant" to "{{tenantCode}}"))

        assertEquals("{{baseUrl}}/api?tenant={{tenantCode}}", url)
    }

    @Test
    fun `round trips a value containing reserved characters`() {
        val rows = listOf(KeyValue.of("q", "a&b=c d+e"), KeyValue.of("token", "{{token}}"))

        val url = QueryParams.replaceQuery("http://localhost/api", QueryParams.pairsForUrl(rows))

        assertEquals(rows.map { it.key to it.value }, QueryParams.parse(url))
    }

    @Test
    fun `rebuilds rows from the url and reuses existing objects`() {
        val existing = KeyValue.of("page", "1")
        val rows = QueryParams.rowsFromUrl("http://localhost/api?page=2&size=10", listOf(existing))

        assertEquals(listOf("page" to "2", "size" to "10"), rows.map { it.key to it.value })
        assertTrue(rows.first() === existing)
    }

    @Test
    fun `disabled rows survive a url edit`() {
        val disabled = KeyValue.of("debug", "true", enabled = false)
        val rows = QueryParams.rowsFromUrl("http://localhost/api?page=2", listOf(disabled))

        assertEquals(listOf("page" to "2", "debug" to "true"), rows.map { it.key to it.value })
        assertFalse(rows.last().enabled)
    }

    @Test
    fun `url parameters enable a row that was disabled`() {
        val disabled = KeyValue.of("debug", "true", enabled = false)
        val rows = QueryParams.rowsFromUrl("http://localhost/api?debug=false", listOf(disabled))

        assertEquals(1, rows.size)
        assertTrue(rows.first().enabled)
        assertEquals("false", rows.first().value)
    }

    @Test
    fun `unknown rows are kept only when asked to`() {
        val imported = KeyValue.of("name", "fast")

        assertTrue(QueryParams.rowsFromUrl("http://localhost/api?page=2", listOf(imported)).none { it.key == "name" })
        assertTrue(
            QueryParams.rowsFromUrl("http://localhost/api?page=2", listOf(imported), keepUnknown = true)
                .any { it.key == "name" }
        )
    }

    @Test
    fun `pairs for the url skip disabled and blank rows`() {
        val rows = listOf(
            KeyValue.of("page", "2"),
            KeyValue.of("off", "1", enabled = false),
            KeyValue.of(" ", "blank")
        )

        assertEquals(listOf("page" to "2"), QueryParams.pairsForUrl(rows))
    }
}
