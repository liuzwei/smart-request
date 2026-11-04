package dev.smartrequest.extract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonPathTest {

    @Test
    fun `reads nested members`() {
        assertEquals("7", found("data.user.id", """{"user":{"id":7}}"""))
    }

    @Test
    fun `data alone returns the compact body`() {
        assertEquals("""{"a":1,"b":[2,3]}""", found("data", """{"a":1,"b":[2,3]}"""))
    }

    @Test
    fun `dollar and body are aliases for data`() {
        assertEquals("1", found("$.a", """{"a":1}"""))
        assertEquals("2", found("body.b", """{"b":2}"""))
    }

    @Test
    fun `reads array items by index`() {
        assertEquals("second", found("data.items[1].name", """{"items":[{"name":"first"},{"name":"second"}]}"""))
    }

    @Test
    fun `negative index counts from the end`() {
        assertEquals("last", found("data.items[-1].name", """{"items":[{"name":"first"},{"name":"last"}]}"""))
    }

    @Test
    fun `root array is addressable`() {
        assertEquals("a", found("data[0]", """["a","b"]"""))
    }

    @Test
    fun `quoted bracket keys allow dots in key names`() {
        assertEquals("v", found("""data["user.name"]""", """{"user.name":"v"}"""))
        assertEquals("v", found("""data['a b']""", """{"a b":"v"}"""))
    }

    @Test
    fun `numbers keep their original text`() {
        assertEquals("12345678901234567890", found("data.id", """{"id":12345678901234567890}"""))
        assertEquals("1.50", found("data.price", """{"price":1.50}"""))
        assertEquals("1e3", found("data.value", """{"value":1e3}"""))
    }

    @Test
    fun `booleans and escaped strings are decoded`() {
        assertEquals("true", found("data.ok", """{"ok":true}"""))
        assertEquals("line\nbreak", found("data.text", """{"text":"line\nbreak"}"""))
        assertEquals("你好", found("data.name", """{"name":"\u4f60\u597d"}"""))
    }

    @Test
    fun `objects and arrays are rendered as compact json`() {
        assertEquals("""{"b":[1,2]}""", found("data.a", """{"a":{"b":[1,2]}}"""))
        assertEquals("""[1,"x"]""", found("data.a", """{"a":[1,"x"]}"""))
    }

    @Test
    fun `missing keys explain where the lookup stopped`() {
        val message = failure("data.user.id", """{"user":{}}""")
        assertTrue(message.contains("key 'id'"), message)
        assertTrue(message.contains("'data.user'"), message)
    }

    @Test
    fun `null values fail instead of writing an empty variable`() {
        val message = failure("data.token", """{"token":null}""")
        assertTrue(message.contains("is null"), message)
    }

    @Test
    fun `wrong container types are reported`() {
        assertTrue(failure("data.items[0]", """{"items":{}}""").contains("not an array"))
        assertTrue(failure("data.items.name", """{"items":[1]}""").contains("not an object"))
    }

    @Test
    fun `out of bounds indexes mention the size`() {
        val message = failure("data.items[5]", """{"items":[1,2]}""")
        assertTrue(message.contains("out of bounds"), message)
        assertTrue(message.contains("size 2"), message)
    }

    @Test
    fun `invalid json is rejected by the reader`() {
        assertNull(JsonReader.parse(""))
        assertNull(JsonReader.parse("""{"a":1"""))
        assertNull(JsonReader.parse("""{"a":1} trailing"""))
        assertNull(JsonReader.parse("""{"a":}"""))
        assertNotNull(JsonReader.parse("""{"a":[1,{"b":null}]}"""))
    }

    @Test
    fun `header sources keep the header name`() {
        val path = JsonPath.parse("headers.X-Request-Id")
        assertEquals(PathRoot.HEADERS, path.root)
        assertEquals("X-Request-Id", path.headerName)
        assertEquals(PathRoot.HEADERS, JsonPath.parse("header.Content-Type").root)
    }

    @Test
    fun `status sources do not take a path`() {
        assertEquals(PathRoot.STATUS, JsonPath.parse("status").root)
        assertEquals(PathRoot.STATUS, JsonPath.parse("statusCode").root)
        assertEquals(PathRoot.STATUS_TEXT, JsonPath.parse("statusText").root)
    }

    @Test
    fun `syntax errors are described`() {
        assertSyntaxError("", "empty")
        assertSyntaxError("unknown.path", "Unknown source")
        assertSyntaxError("data.", "Missing a key")
        assertSyntaxError("data.items[abc]", "Use [0]")
        assertSyntaxError("data.items[0", "Missing ']'")
        assertSyntaxError("headers", "Add a header name")
        assertSyntaxError("status.x", "does not take a path")
        assertSyntaxError("data.user name", "Unexpected character")
    }

    private fun found(source: String, body: String): String =
        (evaluate(source, body) as Evaluation.Found).value

    private fun failure(source: String, body: String): String =
        (evaluate(source, body) as Evaluation.Failure).message

    private fun evaluate(source: String, body: String): Evaluation {
        val root = JsonReader.parse(body) ?: error("test body is not JSON")
        return JsonPath.evaluate(JsonPath.parse(source), root)
    }

    private fun assertSyntaxError(source: String, expected: String) {
        val error = assertThrows(PathSyntaxException::class.java) { JsonPath.parse(source) }
        assertTrue(error.message.orEmpty().contains(expected), "was: ${error.message}")
    }
}
