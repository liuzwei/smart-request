package dev.smartrequest.scenario

import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.service.SmartRequestProjectService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioImporterTest {

    private fun bundle(): ScenarioBundle {
        val start = NodeSpec(
            id = "start",
            kind = ScenarioNode.KIND_START,
            requestKey = null,
            name = "Start",
            x = -280,
            y = 0,
            expectStatus = "",
            timeoutMs = null,
            retries = 0,
            outputs = emptyList()
        )
        val login = NodeSpec(
            id = "login",
            kind = ScenarioNode.KIND_REQUEST,
            requestKey = "r1",
            name = "",
            x = 0,
            y = 0,
            expectStatus = "2xx",
            timeoutMs = 5_000,
            retries = 2,
            outputs = listOf(
                ResponseExtractor(variable = "token", source = "data.token", required = false)
            )
        )
        val orders = NodeSpec(
            id = "orders",
            kind = ScenarioNode.KIND_REQUEST,
            requestKey = "r2",
            name = "",
            x = 280,
            y = 0,
            expectStatus = "",
            timeoutMs = null,
            retries = 0,
            outputs = emptyList()
        )
        val end = NodeSpec(
            id = "end",
            kind = ScenarioNode.KIND_END,
            requestKey = null,
            name = "End",
            x = 560,
            y = 0,
            expectStatus = "",
            timeoutMs = null,
            retries = 0,
            outputs = emptyList()
        )
        return ScenarioBundle(
            name = "Checkout",
            notes = "shared flow",
            maxParallel = 2,
            parameters = listOf(KeyValue.of("tenant", "acme")),
            requests = listOf(
                RequestSpec(
                    "r1",
                    "Auth",
                    HttpRequestConfig(name = "Login", method = "POST", url = "{{baseUrl}}/login")
                ),
                RequestSpec(
                    "r2",
                    "Shop",
                    HttpRequestConfig(name = "Orders", method = "GET", url = "{{baseUrl}}/orders")
                )
            ),
            nodes = listOf(start, login, orders, end),
            edges = listOf(
                EdgeSpec("start", "login"),
                EdgeSpec("login", "orders"),
                EdgeSpec("orders", "end")
            )
        )
    }

    @Test
    fun `importing creates the requests and remaps the scenario nodes`() {
        val service = SmartRequestProjectService()
        var changes = 0
        service.addChangeListener { changes++ }

        val scenario = ScenarioImporter.apply(service, bundle())

        assertEquals(1, changes)
        assertEquals("Checkout", scenario.name)
        assertEquals("shared flow", scenario.notes)
        assertEquals(2, scenario.maxParallel)
        assertEquals("tenant", scenario.parameters.single().key)
        assertEquals(1, service.scenarios().size)
        assertSame(scenario, service.scenarios().single())

        val auth = service.findGroupByName("Auth")
        val shop = service.findGroupByName("Shop")
        assertNotNull(auth)
        assertNotNull(shop)
        assertEquals("Login", auth!!.requests.single().name)
        assertEquals("Orders", shop!!.requests.single().name)

        val requests = ScenarioGraph.requestNodes(scenario)
        assertEquals(2, requests.size)
        val login = requests.first { it.expectStatus == "2xx" }
        val orders = requests.first { it.x == 280 }
        assertEquals(auth.requests.single().id, login.requestId)
        assertEquals(shop.requests.single().id, orders.requestId)
        assertEquals(5_000, login.timeoutMs)
        assertEquals(2, login.retries)
        assertEquals(1, login.outputs.size)
        assertEquals("token", login.outputs.single().variable)
        assertEquals(false, login.outputs.single().required)
        assertNotEquals("start", login.id)
        assertTrue(scenario.edges.any { it.from == login.id && it.to == orders.id })
        assertNotNull(ScenarioGraph.start(scenario))
        assertNotNull(ScenarioGraph.end(scenario))
        assertEquals(setOf(login.id, orders.id), ScenarioGraph.requestNodes(scenario).map { it.id }.toSet())
    }

    @Test
    fun `importing the same file twice creates independent copies`() {
        val service = SmartRequestProjectService()

        val first = ScenarioImporter.apply(service, bundle())
        val second = ScenarioImporter.apply(service, bundle())

        assertEquals("Checkout", first.name)
        assertEquals("Checkout 2", second.name)
        assertEquals(2, service.scenarios().size)
        val firstLogin = ScenarioGraph.requestNodes(first).first { it.requestId != null }
        val secondLogin = ScenarioGraph.requestNodes(second).first { it.requestId != null }
        assertNotEquals(firstLogin.requestId, secondLogin.requestId)
        assertNotEquals(firstLogin.id, secondLogin.id)
        assertEquals(2, service.findGroupByName("Auth")!!.requests.size)
        assertEquals("Login", service.findGroupByName("Auth")!!.requests[0].name)
        assertEquals("Login 2", service.findGroupByName("Auth")!!.requests[1].name)
    }

    @Test
    fun `a request key that is not in the file leaves the node without a request`() {
        val service = SmartRequestProjectService()
        val source = bundle()
        val orphan = source.nodes.first { it.kind == ScenarioNode.KIND_REQUEST && it.requestKey == "r2" }
        val bundle = source.copy(
            requests = listOf(source.requests.first()),
            nodes = listOf(source.nodes.first(), orphan, source.nodes.last())
        )

        val scenario = ScenarioImporter.apply(service, bundle)

        val requestNodes = ScenarioGraph.requestNodes(scenario)
        assertEquals(1, requestNodes.size)
        assertNull(requestNodes.single().requestId)
    }

    @Test
    fun `a blank group name goes to the imported requests group`() {
        val service = SmartRequestProjectService()
        val bundle = bundle().copy(
            requests = bundle().requests.map { it.copy(group = "") }
        )

        ScenarioImporter.apply(service, bundle)

        assertEquals(2, service.findGroupByName(ScenarioImporter.DEFAULT_GROUP)!!.requests.size)
    }
}
