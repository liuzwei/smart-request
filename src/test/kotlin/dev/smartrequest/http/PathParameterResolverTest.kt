package dev.smartrequest.http

import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PathParameterResolverTest {

    @Test
    fun `detects path parameters but ignores environment variables`() {
        val names = PathParameterResolver.detectNames(
            "{{baseUrl}}/users/{userId}/orders/{orderId}?filter={{filter}}"
        )

        assertEquals(listOf("userId", "orderId"), names)
    }

    @Test
    fun `resolves and encodes path values`() {
        val result = PathParameterResolver.resolve(
            "http://localhost/users/{id}/files/{name}",
            listOf(
                KeyValue.of("id", "42"),
                KeyValue.of("name", "a/b c")
            ),
            emptyMap()
        )

        assertEquals("http://localhost/users/42/files/a%2Fb%20c", result.url)
        assertEquals(emptySet<String>(), result.unresolved)
    }

    @Test
    fun `resolves variables inside path values`() {
        val result = PathParameterResolver.resolve(
            "http://localhost/users/{id}",
            listOf(KeyValue.of("id", "{{userId}}")),
            mapOf("userId" to "1001")
        )

        assertEquals("http://localhost/users/1001", result.url)
    }

    @Test
    fun `reports unresolved variables in path values`() {
        val result = PathParameterResolver.resolve(
            "http://localhost/users/{id}",
            listOf(KeyValue.of("id", "{{userId}}")),
            emptyMap()
        )

        assertEquals(setOf("userId"), result.unresolved)
    }

    @Test
    fun `rejects missing and empty path values`() {
        assertThrows(RequestBuildException::class.java) {
            PathParameterResolver.resolve("http://localhost/users/{id}", emptyList(), emptyMap())
        }
        assertThrows(RequestBuildException::class.java) {
            PathParameterResolver.resolve(
                "http://localhost/users/{id}",
                listOf(KeyValue.of("id", "  ")),
                emptyMap()
            )
        }
    }
}
