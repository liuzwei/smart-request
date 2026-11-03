package dev.smartrequest.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JsonPrettyPrinterTest {

    @Test
    fun `formats an object`() {
        val pretty = JsonPrettyPrinter.prettyPrintOrNull("""{"name":"Ada","ok":true}""")

        assertEquals(
            """
            {
              "name": "Ada",
              "ok": true
            }
            """.trimIndent(),
            pretty
        )
    }

    @Test
    fun `formats nested arrays and objects`() {
        val pretty = JsonPrettyPrinter.prettyPrintOrNull("""[{"a":[1,2]},{}]""")

        assertEquals(
            """
            [
              {
                "a": [
                  1,
                  2
                ]
              },
              {}
            ]
            """.trimIndent(),
            pretty
        )
    }

    @Test
    fun `keeps escaped quotes inside strings`() {
        val pretty = JsonPrettyPrinter.prettyPrintOrNull("""{"text":"a \"quoted\" brace }"}""")

        assertEquals(
            """
            {
              "text": "a \"quoted\" brace }"
            }
            """.trimIndent(),
            pretty
        )
    }

    @Test
    fun `returns null for invalid json`() {
        assertNull(JsonPrettyPrinter.prettyPrintOrNull("{"))
        assertNull(JsonPrettyPrinter.prettyPrintOrNull("not json"))
        assertNull(JsonPrettyPrinter.prettyPrintOrNull("""{"a":1} trailing"""))
    }
}
