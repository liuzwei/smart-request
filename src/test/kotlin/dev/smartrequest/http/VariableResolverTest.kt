package dev.smartrequest.http

import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VariableResolverTest {

    @Test
    fun `resolves baseUrl and environment variables`() {
        val environment = EnvironmentConfig().apply {
            baseUrl = "http://localhost:8080"
            variables.add(KeyValue.of("token", "abc123"))
            variables.add(KeyValue.of("tenant", "acme"))
        }

        val result = VariableResolver.resolve(
            "{{baseUrl}}/api/{{tenant}}/users?token={{token}}",
            VariableResolver.environmentVariables(environment)
        )

        assertEquals("http://localhost:8080/api/acme/users?token=abc123", result.text)
        assertTrue(result.unresolved.isEmpty())
    }

    @Test
    fun `resolves variables recursively and supports whitespace`() {
        val variables = mapOf(
            "root" to "http://localhost:9090",
            "api" to "{{ root }}/api",
            "users" to "{{api}}/users"
        )

        val result = VariableResolver.resolve("{{users}}", variables)

        assertEquals("http://localhost:9090/api/users", result.text)
        assertTrue(result.unresolved.isEmpty())
    }

    @Test
    fun `reports unknown variables and preserves their text`() {
        val result = VariableResolver.resolve("{{baseUrl}}/{{missing}}", mapOf("baseUrl" to "http://x"))

        assertEquals("http://x/{{missing}}", result.text)
        assertEquals(setOf("missing"), result.unresolved)
    }

    @Test
    fun `stops after repeated cycles`() {
        val result = VariableResolver.resolve("{{a}}", mapOf("a" to "{{b}}", "b" to "{{a}}"))

        assertTrue(result.hasUnresolved)
    }

    @Test
    fun `environment variables skip disabled and blank entries`() {
        val environment = EnvironmentConfig().apply {
            baseUrl = "http://localhost:8080"
            variables.add(KeyValue.of("enabled", "yes"))
            variables.add(KeyValue.of("disabled", "no", enabled = false))
            variables.add(KeyValue.of("", "ignored"))
        }

        val variables = VariableResolver.environmentVariables(environment)

        assertEquals("http://localhost:8080", variables["baseUrl"])
        assertEquals("yes", variables["enabled"])
        assertFalse(variables.containsKey("disabled"))
    }

    @Test
    fun `explicit baseUrl variable overrides the environment field`() {
        val environment = EnvironmentConfig().apply {
            baseUrl = "http://localhost:8080"
            variables.add(KeyValue.of("baseUrl", "https://override.example"))
        }

        assertEquals("https://override.example", VariableResolver.environmentVariables(environment)["baseUrl"])
    }
}
