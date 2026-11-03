package dev.smartrequest.spring

import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpringRequestBodyGeneratorParamsTest {

    @Test
    fun `builds a typed json object from enabled query parameters`() {
        val params = listOf(
            KeyValue.of("q", "hello"),
            KeyValue.of("page", "1"),
            KeyValue.of("active", "true"),
            KeyValue.of("raw", """{"nested":1}"""),
            KeyValue.of("ignored", "x", enabled = false)
        )

        assertEquals(
            """
            {
              "q": "hello",
              "page": 1,
              "active": true,
              "raw": {
                "nested": 1
              }
            }
            """.trimIndent(),
            SpringRequestBodyGenerator.fromParams(params)
        )
    }
}
