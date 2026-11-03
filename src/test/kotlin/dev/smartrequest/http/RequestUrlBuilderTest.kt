package dev.smartrequest.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.smartrequest.model.KeyValue

class RequestUrlBuilderTest {

    @Test
    fun `parameters own the query string and the fragment is preserved`() {
        val result = RequestUrlBuilder.build(
            "{{baseUrl}}/users?active=true#section",
            listOf("page" to "1", "q" to "hello world"),
            mapOf("baseUrl" to "http://localhost:8080")
        )

        assertEquals("http://localhost:8080/users?page=1&q=hello%20world#section", result.url)
    }

    @Test
    fun `a url without parameters keeps its own query`() {
        val result = RequestUrlBuilder.build("http://localhost:8080/users?active=true", emptyList())

        assertEquals("http://localhost:8080/users?active=true", result.url)
    }

    @Test
    fun `a url query is replaced rather than doubled when parameters exist`() {
        val result = RequestUrlBuilder.build(
            "http://localhost:8080/users?page=1",
            listOf("page" to "2")
        )

        assertEquals("http://localhost:8080/users?page=2", result.url)
    }

    @Test
    fun `adds http scheme for host without one`() {
        val result = RequestUrlBuilder.build("localhost:8080/api", emptyList())

        assertEquals("http://localhost:8080/api", result.url)
    }

    @Test
    fun `encodes parameter keys and values`() {
        val result = RequestUrlBuilder.build(
            "https://example.com/search",
            listOf("q" to "a+b&c")
        )

        assertEquals("https://example.com/search?q=a%2Bb%26c", result.url)
    }

    @Test
    fun `resolves variables in parameters`() {
        val result = RequestUrlBuilder.build(
            "{{baseUrl}}/users",
            listOf("tenant" to "{{tenantCode}}"),
            mapOf("baseUrl" to "http://localhost:8080", "tenantCode" to "zoogoo")
        )

        assertEquals("http://localhost:8080/users?tenant=zoogoo", result.url)
    }

    @Test
    fun `keeps unresolved variables for the preparer to report`() {
        val result = RequestUrlBuilder.build("{{baseUrl}}/users", emptyList(), emptyMap())

        assertEquals("http://{{baseUrl}}/users", result.url)
        assertEquals(setOf("baseUrl"), result.unresolved)
    }

    @Test
    fun `rejects relative urls`() {
        assertThrows(RequestBuildException::class.java) {
            RequestUrlBuilder.build("/users", emptyList())
        }
    }

    @Test
    fun `rejects unsupported schemes`() {
        val error = assertThrows(RequestBuildException::class.java) {
            RequestUrlBuilder.build("ftp://example.com/file", emptyList())
        }
        assertTrue(error.message!!.contains("http"))
    }

    @Test
    fun `rejects user info`() {
        assertThrows(RequestBuildException::class.java) {
            RequestUrlBuilder.build("http://user:pass@example.com/api", emptyList())
        }
    }

    @Test
    fun `substitutes path parameters before adding query parameters`() {
        val result = RequestUrlBuilder.build(
            "{{baseUrl}}/users/{id}",
            listOf("page" to "1"),
            mapOf("baseUrl" to "http://localhost:8080"),
            listOf(KeyValue.of("id", "42"))
        )

        assertEquals("http://localhost:8080/users/42?page=1", result.url)
    }

    @Test
    fun `rejects a missing path parameter`() {
        assertThrows(RequestBuildException::class.java) {
            RequestUrlBuilder.build("http://localhost/users/{id}", emptyList(), emptyMap(), emptyList())
        }
    }
}
