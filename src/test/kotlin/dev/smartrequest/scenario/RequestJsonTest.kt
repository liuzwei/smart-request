package dev.smartrequest.scenario

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.model.ResponseExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestJsonTest {

    private fun login(): HttpRequestConfig = HttpRequestConfig(
        name = "Login",
        method = "POST",
        url = "{{baseUrl}}/login",
        headers = mutableListOf(KeyValue.of("X-Test", "yes")),
        pathParams = mutableListOf(KeyValue.of("id", "{{userId}}")),
        params = mutableListOf(KeyValue.of("page", "2")),
        bodyType = BodyType.JSON.name,
        body = """{"user":"ada"}""",
        extractors = mutableListOf(
            ResponseExtractor(variable = "token", source = "data.token", required = false)
        ),
        useGlobalHeaders = false,
        useGlobalParams = true
    )

    private fun groups(): List<RequestGroup> = listOf(
        RequestGroup(name = "Auth", requests = mutableListOf(login())),
        RequestGroup(name = "Shop", requests = mutableListOf(HttpRequestConfig(name = "Cart", url = "{{baseUrl}}/cart")))
    )

    @Test
    fun `groups round-trip through the JSON file`() {
        val text = RequestJson.encode(groups())

        val decoded = RequestJson.decode(text)

        assertEquals(listOf("Auth", "Shop"), decoded.map { it.name })
        val auth = decoded[0].requests.single()
        assertEquals("Login", auth.name)
        assertEquals("POST", auth.method)
        assertEquals("{{baseUrl}}/login", auth.url)
        assertEquals(listOf("X-Test" to "yes"), auth.headers.map { it.key to it.value })
        assertEquals(listOf("id" to "{{userId}}"), auth.pathParams.map { it.key to it.value })
        assertEquals(listOf("page" to "2"), auth.params.map { it.key to it.value })
        assertEquals(BodyType.JSON.name, auth.bodyType)
        assertEquals("""{"user":"ada"}""", auth.body)
        val extractor = auth.extractors.single()
        assertEquals("token", extractor.variable)
        assertEquals("data.token", extractor.source)
        assertEquals(false, extractor.required)
        assertEquals(false, auth.useGlobalHeaders)
        assertEquals(true, auth.useGlobalParams)
        assertEquals("Cart", decoded[1].requests.single().name)
    }

    @Test
    fun `encoding is deterministic and filters empty groups`() {
        val empty = RequestGroup(name = "Empty")
        val first = RequestJson.encode(groups() + empty)
        val second = RequestJson.encode(groups() + empty)

        assertEquals(first, second)
        assertTrue(!first.contains("\"Empty\""))
    }

    @Test
    fun `single request export keeps its group name`() {
        val text = RequestJson.encodeSingle(login(), "Auth")

        val decoded = RequestJson.decode(text)

        assertEquals("Auth", decoded.single().name)
        assertEquals("Login", decoded.single().requests.single().name)
    }

    @Test
    fun `duplicate request ids are not reused on decode`() {
        val text = RequestJson.encode(groups())

        val decoded = RequestJson.decode(text)

        val ids = decoded.flatMap { group -> group.requests.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `unicode and quotes survive the round trip`() {
        val request = HttpRequestConfig(
            name = "查询 \"用户\"",
            url = "{{baseUrl}}/查询?q=中文",
            body = """{"名字":"张三", "note":"a\"b\\c"}"""
        )
        val text = RequestJson.encode(listOf(RequestGroup(name = "中文组", requests = mutableListOf(request))))

        val decoded = RequestJson.decode(text).single().requests.single()

        assertEquals("查询 \"用户\"", decoded.name)
        assertEquals("{{baseUrl}}/查询?q=中文", decoded.url)
        assertEquals("""{"名字":"张三", "note":"a\"b\\c"}""", decoded.body)
    }

    @Test
    fun `invalid input is rejected with a readable message`() {
        assertThrows(RequestJsonException::class.java) { RequestJson.decode("not json") }
        assertThrows(RequestJsonException::class.java) {
            RequestJson.decode("""{"format":"other","version":1,"groups":[]}""")
        }
        assertThrows(RequestJsonException::class.java) {
            RequestJson.decode("""{"format":"smart-request-requests","version":99,"groups":[]}""")
        }
        assertThrows(RequestJsonException::class.java) {
            RequestJson.decode("""{"format":"smart-request-requests","version":1,"groups":{}}""")
        }
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val text = """
            {
              "format": "smart-request-requests",
              "version": 1,
              "groups": [
                {
                  "name": "Minimal",
                  "requests": [ { "url": "{{baseUrl}}/x" } ]
                }
              ]
            }
        """.trimIndent()

        val request = RequestJson.decode(text).single().requests.single()

        assertEquals("New Request", request.name)
        assertEquals("GET", request.method)
        assertEquals("{{baseUrl}}/x", request.url)
        assertEquals(BodyType.NONE.name, request.bodyType)
        assertTrue(request.useGlobalHeaders)
        assertTrue(request.useGlobalParams)
    }
}