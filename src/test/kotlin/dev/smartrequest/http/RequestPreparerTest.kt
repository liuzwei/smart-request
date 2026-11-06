package dev.smartrequest.http

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestPreparerTest {

    private val environment = EnvironmentConfig().apply {
        baseUrl = "http://localhost:8080"
        variables.add(KeyValue.of("token", "secret"))
    }

    @Test
    fun `request headers override globals`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/hello"
            headers.add(KeyValue.of("Authorization", "Bearer request"))
        }
        val globals = listOf(
            KeyValue.of("Authorization", "Bearer global"),
            KeyValue.of("X-Tenant", "acme")
        )

        val prepared = RequestPreparer.prepare(request, environment, globals, emptyList(), 30_000, true)

        assertEquals("Bearer request", header(prepared.request.headers, "Authorization"))
        assertEquals("acme", header(prepared.request.headers, "X-Tenant"))
    }

    @Test
    fun `request params override globals`() {
        val requestParams = listOf(KeyValue.of("page", "2"))
        val globalParams = listOf(KeyValue.of("page", "1"), KeyValue.of("tenant", "acme"))

        val merged = RequestPreparer.mergeParameters(requestParams, globalParams)

        assertEquals(listOf("page" to "2", "tenant" to "acme"), merged)
    }

    @Test
    fun `path parameters are applied to the final url`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/users/{id}"
            pathParams.add(KeyValue.of("id", "42"))
        }

        val prepared = RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)

        assertEquals("http://localhost:8080/users/42", prepared.request.url)
    }

    @Test
    fun `missing path parameter fails before the request is sent`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/users/{id}"
        }

        val error = assertThrows(RequestBuildException::class.java) {
            RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)
        }
        assertTrue(error.message!!.contains("id"))
    }

    @Test
    fun `resolves headers body and variables`() {
        val request = HttpRequestConfig().apply {
            method = "POST"
            url = "{{baseUrl}}/users"
            headers.add(KeyValue.of("Authorization", "Bearer {{token}}"))
            bodyType = BodyType.JSON.name
            body = """{"name":"{{token}}"}"""
        }

        val prepared = RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)

        assertEquals("http://localhost:8080/users", prepared.request.url)
        assertEquals("Bearer secret", header(prepared.request.headers, "Authorization"))
        assertEquals("application/json; charset=UTF-8", header(prepared.request.headers, "Content-Type"))
        assertEquals("""{"name":"secret"}""", prepared.request.body)
    }

    @Test
    fun `functions can be used in url query headers and body`() {
        val request = HttpRequestConfig().apply {
            method = "POST"
            url = "{{baseUrl}}/api/{{urlencode(name)}}"
            params.add(KeyValue.of("q", "{{base64(token)}}"))
            headers.add(KeyValue.of("X-Hash", "{{sha256(payload)}}"))
            bodyType = BodyType.JSON.name
            body = """{"token":"{{base64(token)}}"}"""
        }

        val prepared = RequestPreparer.prepare(
            request,
            environment,
            emptyList(),
            emptyList(),
            30_000,
            true,
            variables = mapOf(
                "name" to "a b",
                "token" to "secret",
                "payload" to "hello"
            )
        )

        assertEquals("http://localhost:8080/api/a%20b?q=c2VjcmV0", prepared.request.url)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            header(prepared.request.headers, "X-Hash")
        )
        assertEquals("""{"token":"c2VjcmV0"}""", prepared.request.body)
    }

    @Test
    fun `unknown functions fail before the request is sent`() {
        val request = HttpRequestConfig().apply {
            method = "POST"
            url = "{{baseUrl}}/users"
            bodyType = BodyType.JSON.name
            body = """{"token":"{{nope(token)}}"}"""
        }

        val error = assertThrows(RequestBuildException::class.java) {
            RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)
        }
        assertTrue(error.message!!.contains("Unknown function 'nope'"))
    }

    @Test
    fun `no body means null body`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/hello"
            bodyType = BodyType.NONE.name
            body = "ignored"
        }

        val prepared = RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)

        assertNull(prepared.request.body)
    }

    @Test
    fun `unresolved variables fail before the request is sent`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/users/{{userId}}"
        }

        val error = assertThrows(RequestBuildException::class.java) {
            RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)
        }
        assertTrue(error.message!!.contains("userId"))
    }

    @Test
    fun `reserved headers are filtered`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/hello"
            headers.add(KeyValue.of("Host", "evil.example"))
            headers.add(KeyValue.of("Content-Length", "10"))
            headers.add(KeyValue.of("X-Trace", "abc"))
        }

        val prepared = RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true)

        assertNull(header(prepared.request.headers, "Host"))
        assertNull(header(prepared.request.headers, "Content-Length"))
        assertEquals("abc", header(prepared.request.headers, "X-Trace"))
    }

    @Test
    fun `run variables override environment variables`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/users"
            headers.add(KeyValue.of("Authorization", "Bearer {{token}}"))
            bodyType = BodyType.JSON.name
            body = """{"token":"{{token}}"}"""
        }

        val prepared = RequestPreparer.prepare(
            request,
            environment,
            emptyList(),
            emptyList(),
            30_000,
            true,
            mapOf("token" to "from-run")
        )

        assertEquals("Bearer from-run", header(prepared.request.headers, "Authorization"))
        assertEquals("""{"token":"from-run"}""", prepared.request.body)
    }

    @Test
    fun `run variables are used in urls`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/orders/{{orderId}}"
        }

        val prepared = RequestPreparer.prepare(
            request,
            environment,
            emptyList(),
            emptyList(),
            30_000,
            true,
            mapOf("orderId" to "42")
        )

        assertEquals("http://localhost:8080/orders/42", prepared.request.url)
    }

    @Test
    fun `a variable missing from both the environment and the run scope is reported`() {
        val request = HttpRequestConfig().apply {
            url = "{{baseUrl}}/orders/{{orderId}}"
        }

        val error = assertThrows(RequestBuildException::class.java) {
            RequestPreparer.prepare(request, environment, emptyList(), emptyList(), 30_000, true, emptyMap())
        }

        assertTrue(error.message!!.contains("orderId"))
    }

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}
