package dev.smartrequest.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TemplateEngineTest {

    @Test
    fun `plain variables keep working`() {
        val result = TemplateEngine.resolve(
            "http://localhost/{{tenant}}/users?token={{token}}",
            mapOf("tenant" to "acme", "token" to "abc")
        )

        assertEquals("http://localhost/acme/users?token=abc", result.text)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `base64 can be applied to a variable`() {
        val result = TemplateEngine.resolve(
            """{"token":"{{base64(token)}}"}""",
            mapOf("token" to "hello")
        )

        assertEquals("""{"token":"aGVsbG8="}""", result.text)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `functions can be nested and take string arguments`() {
        val result = TemplateEngine.resolve(
            "{{concat('Bearer ', base64(token))}}",
            mapOf("token" to "hello")
        )

        assertEquals("Bearer aGVsbG8=", result.text)
    }

    @Test
    fun `whitespace around the expression and arguments is ignored`() {
        val result = TemplateEngine.resolve(
            "{{ base64 ( token ) }}",
            mapOf("token" to "hello")
        )

        assertEquals("aGVsbG8=", result.text)
    }

    @Test
    fun `a quoted argument may contain a closing brace pair`() {
        val result = TemplateEngine.resolve("{{concat('}}', token)}}", mapOf("token" to "x"))
        assertEquals("}}x", result.text)
    }

    @Test
    fun `an unknown function is an error`() {
        val result = TemplateEngine.resolve("{{nope(token)}}", mapOf("token" to "x"))
        assertEquals("{{nope(token)}}", result.text)
        assertTrue(result.errors.single().contains("Unknown function 'nope'"))
    }

    @Test
    fun `wrong arity is reported before sending`() {
        val result = TemplateEngine.resolve("{{base64(token, x)}}", mapOf("token" to "x"))
        assertTrue(result.errors.single().contains("expects 1 argument(s), got 2"))
    }

    @Test
    fun `a missing function argument variable is reported by name`() {
        val result = TemplateEngine.resolve("{{base64(token)}}", emptyMap())
        assertEquals(setOf("token"), result.unresolved)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `variable values are templates and may call functions`() {
        val variables = mapOf(
            "token" to "hello",
            "encoded" to "{{base64(token)}}"
        )
        assertEquals("aGVsbG8=", TemplateEngine.resolve("{{encoded}}", variables).text)
    }

    @Test
    fun `function results are not rescanned as templates`() {
        val result = TemplateEngine.resolve("{{concat('{{', 'token}}')}}", emptyMap())
        assertEquals("{{token}}", result.text)
    }

    @Test
    fun `circular variable references are reported`() {
        val variables = mapOf("a" to "{{b}}", "b" to "{{a}}")
        val result = TemplateEngine.resolve("{{a}}", variables)
        assertTrue(result.errors.any { it.contains("Circular variable reference") })
    }

    @Test
    fun `analysis extracts only real variables from function arguments`() {
        val analysis = TemplateEngine.analyze("""{"token":"{{base64(token)}}","name":"{{concat('x', tenant)}}"}""")
        assertEquals(setOf("token", "tenant"), analysis.variables)
        assertEquals(setOf("base64", "concat"), analysis.functions)
        assertTrue(analysis.issues.isEmpty())
    }

    @Test
    fun `analysis reports unknown functions and syntax errors`() {
        val analysis = TemplateEngine.analyze("{{missing(token)}} {{token")
        assertTrue(analysis.issues.any { it.message.contains("Unknown function") })
        assertTrue(analysis.issues.any { it.message.contains("Unclosed template expression") })
        assertTrue(analysis.variables.contains("token"))
    }

    @Test
    fun `unresolved variables are not reported as function errors`() {
        val analysis = TemplateEngine.analyze("{{base64(token)}}")
        assertTrue(analysis.issues.isEmpty())
        assertFalse(analysis.variables.isEmpty())
    }

    @Test
    fun `pure macros can be composed`() {
        val definition = dev.smartrequest.model.FunctionDefinition().apply {
            name = "bearer"
            parameters = mutableListOf("value")
            expression = "concat('Bearer ', base64(value))"
        }
        val registry = FunctionRegistry.withMacros(listOf(definition))
        val result = TemplateEngine.resolve("{{bearer(token)}}", mapOf("token" to "hello"), registry)
        assertEquals("Bearer aGVsbG8=", result.text)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.unresolved.isEmpty())
    }

    @Test
    fun `macros can call other macros`() {
        val encode = dev.smartrequest.model.FunctionDefinition().apply {
            name = "encode"
            parameters = mutableListOf("value")
            expression = "base64(value)"
        }
        val bearer = dev.smartrequest.model.FunctionDefinition().apply {
            name = "bearer"
            parameters = mutableListOf("value")
            expression = "concat('Bearer ', encode(value))"
        }
        val registry = FunctionRegistry.withMacros(listOf(encode, bearer))
        assertEquals(
            "Bearer aGVsbG8=",
            TemplateEngine.resolve("{{bearer(token)}}", mapOf("token" to "hello"), registry).text
        )
    }

    @Test
    fun `macro recursion is bounded`() {
        val loop = dev.smartrequest.model.FunctionDefinition().apply {
            name = "loop"
            parameters = mutableListOf("value")
            expression = "loop(value)"
        }
        val registry = FunctionRegistry.withMacros(listOf(loop))
        val result = TemplateEngine.resolve("{{loop(x)}}", mapOf("x" to "1"), registry)
        assertTrue(result.errors.any { it.contains("maximum call depth") })
    }
}
