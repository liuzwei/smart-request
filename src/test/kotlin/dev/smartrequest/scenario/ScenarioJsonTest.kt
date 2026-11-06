package dev.smartrequest.scenario

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioJsonTest {

    private fun scenario(): ScenarioConfig = ScenarioConfig(
        name = "Checkout",
        notes = "Smoke flow",
        maxParallel = 3,
        parameters = mutableListOf(KeyValue.of("tenant", "acme"))
    ).apply {
        normalize()
    }

    private fun requests(): MutableMap<String, HttpRequestConfig> {
        val login = HttpRequestConfig(
            id = "r1",
            name = "Login",
            method = "POST",
            url = "{{baseUrl}}/login",
            headers = mutableListOf(KeyValue.of("X-Test", "yes")),
            bodyType = BodyType.JSON.name,
            body = """{"user":"ada"}""",
            extractors = mutableListOf(ResponseExtractor(variable = "token", source = "data.token")),
            useGlobalHeaders = false
        )
        val cart = HttpRequestConfig(id = "r2", name = "Cart", url = "{{baseUrl}}/cart")
        return mutableMapOf("r1" to login, "r2" to cart)
    }

    private fun encoded(
        scenario: ScenarioConfig = scenario(),
        requests: Map<String, HttpRequestConfig> = requests()
    ): String = ScenarioJson.encode(
        scenario,
        requestProvider = { requests[it] },
        groupProvider = { if (it == "r1") "Auth" else "Shop" }
    )

    @Test
    fun `a scenario round trips through JSON with its requests`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "r1", afterNodeId = null)
        first.expectStatus = "2xx"
        first.timeoutMs = 5_000
        first.retries = 2
        first.outputs.add(
            ResponseExtractor(variable = "token", source = "data.token", required = false)
        )
        val second = ScenarioGraph.addRequestNode(scenario, "r2")
        second.retries = 1

        val bundle = ScenarioJson.decode(encoded(scenario))

        assertEquals("Checkout", bundle.name)
        assertEquals("Smoke flow", bundle.notes)
        assertEquals(3, bundle.maxParallel)
        assertEquals("tenant", bundle.parameters.single().key)
        assertEquals(listOf("r1", "r2"), bundle.requests.map { it.key })
        assertEquals("Auth", bundle.requests.first { it.key == "r1" }.group)
        val login = bundle.requests.first { it.key == "r1" }.request
        assertEquals("Login", login.name)
        assertEquals("POST", login.method)
        assertEquals("{{baseUrl}}/login", login.url)
        assertEquals("yes", login.headers.single().value)
        assertEquals(BodyType.JSON.name, login.bodyType)
        assertEquals("""{"user":"ada"}""", login.body)
        assertEquals("data.token", login.extractors.single().source)
        assertEquals(false, login.useGlobalHeaders)
        assertEquals(true, login.useGlobalParams)

        assertEquals(4, bundle.nodes.size)
        assertEquals(3, bundle.edges.size) // start -> r1, r1 -> r2, r2 -> end
        val firstNode = bundle.nodes.first { it.requestKey == "r1" }
        assertEquals(ScenarioNode.KIND_REQUEST, firstNode.kind)
        assertEquals("2xx", firstNode.expectStatus)
        assertEquals(5_000, firstNode.timeoutMs)
        assertEquals(2, firstNode.retries)
        assertEquals("token", firstNode.outputs.single().variable)
        assertEquals(false, firstNode.outputs.single().required)
        assertNotNull(bundle.nodes.firstOrNull { it.kind == ScenarioNode.KIND_START })
        assertNotNull(bundle.nodes.firstOrNull { it.kind == ScenarioNode.KIND_END })
        assertTrue(bundle.edges.any { edge -> edge.from == first.id && edge.to == second.id })
    }

    @Test
    fun `encoding is deterministic`() {
        val scenario = scenario()
        ScenarioGraph.addRequestNode(scenario, "r1", afterNodeId = null)
        ScenarioGraph.addRequestNode(scenario, "r2")

        assertEquals(encoded(scenario), encoded(scenario))
    }

    @Test
    fun `quotes newlines and unicode survive the round trip`() {
        val scenario = scenario().apply {
            name = "Order \"quoted\"\nflow"
            notes = "tab\tand smile 🙂"
        }
        val first = ScenarioGraph.addRequestNode(scenario, "r1", afterNodeId = null)
        first.outputs.add(ResponseExtractor(variable = "x", source = "data.x", required = false))
        val requests = requests().apply {
            getValue("r1").body = """{"text":"line1\n\"line2\"","emoji":"🙂"}"""
        }

        val bundle = ScenarioJson.decode(encoded(scenario, requests))

        assertEquals("Order \"quoted\"\nflow", bundle.name)
        assertEquals("tab\tand smile 🙂", bundle.notes)
        assertEquals(
            """{"text":"line1\n\"line2\"","emoji":"🙂"}""",
            bundle.requests.first { it.key == "r1" }.request.body
        )
        assertEquals(false, bundle.nodes.first { it.requestKey == "r1" }.outputs.single().required)
    }

    @Test
    fun `a missing request is exported as a node without a request`() {
        val scenario = scenario()
        val node = ScenarioGraph.addRequestNode(scenario, "missing-request", afterNodeId = null)
        assertNull(encoded(scenario, emptyMap()).let { ScenarioJson.decode(it) }
            .nodes.first { it.id == node.id }.requestKey)
    }

    @Test
    fun `invalid files produce clear errors`() {
        assertThrows(ScenarioJsonException::class.java) { ScenarioJson.decode("{not json") }
        assertThrows(ScenarioJsonException::class.java) {
            ScenarioJson.decode("""{"format":"other","version":1,"nodes":[]}""")
        }
        assertThrows(ScenarioJsonException::class.java) {
            ScenarioJson.decode("""{"format":"smart-request-scenario","version":99,"nodes":[]}""")
        }
        assertThrows(ScenarioJsonException::class.java) {
            ScenarioJson.decode("""{"format":"smart-request-scenario","version":1,"nodes":[1]}""")
        }
        assertThrows(ScenarioJsonException::class.java) {
            ScenarioJson.decode("""{"format":"smart-request-scenario","version":1,"nodes":{}}""")
        }
        assertThrows(ScenarioJsonException::class.java) {
            ScenarioJson.decode(
                """{"format":"smart-request-scenario","version":1,"nodes":[],"edges":[{"from":""}]}"""
            )
        }
    }

    @Test
    fun `defaults are applied when optional fields are absent`() {
        val bundle = ScenarioJson.decode(
            """{"format":"smart-request-scenario","version":1,"nodes":[{"kind":"START"}]}"""
        )

        assertEquals("Imported Scenario", bundle.name)
        assertEquals("", bundle.notes)
        assertEquals(ScenarioConfig.DEFAULT_MAX_PARALLEL, bundle.maxParallel)
        assertTrue(bundle.requests.isEmpty())
        assertEquals(1, bundle.nodes.size)
        assertEquals(0, bundle.nodes.single().retries)
        assertEquals(0, bundle.nodes.single().x)
    }
}
