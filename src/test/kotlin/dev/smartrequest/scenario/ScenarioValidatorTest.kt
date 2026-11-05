package dev.smartrequest.scenario

import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioValidatorTest {

    private val requests = HashMap<String, HttpRequestConfig>()

    private fun request(id: String, url: String = "{{baseUrl}}/$id"): HttpRequestConfig =
        HttpRequestConfig(id = id, url = url).also { requests[id] = it }

    private fun validate(
        scenario: ScenarioConfig,
        environment: Map<String, String> = mapOf("baseUrl" to "http://test"),
        parameters: Map<String, String> = emptyMap()
    ): ScenarioValidator.Report = ScenarioValidator.validate(scenario, { requests[it] }, environment, parameters)

    private fun start() = ScenarioNode(kind = ScenarioNode.KIND_START, name = "Start")

    private fun end() = ScenarioNode(kind = ScenarioNode.KIND_END, name = "End", x = 600)

    private fun node(id: String, requestId: String, vararg outputs: ResponseExtractor): ScenarioNode =
        ScenarioNode(
            id = id,
            kind = ScenarioNode.KIND_REQUEST,
            requestId = requestId,
            x = 200,
            outputs = outputs.toMutableList()
        )

    private fun edge(from: ScenarioNode, to: ScenarioNode) = ScenarioEdge(from = from.id, to = to.id)

    private fun scenario(
        vararg nodes: ScenarioNode,
        edges: List<ScenarioEdge> = emptyList(),
        maxParallel: Int = ScenarioConfig.DEFAULT_MAX_PARALLEL
    ): ScenarioConfig = ScenarioConfig(
        name = "flow",
        maxParallel = maxParallel,
        nodes = nodes.toMutableList(),
        edges = edges.toMutableList()
    )

    @Test
    fun `a connected scenario with a captured value is valid`() {
        val loginRequest = request("login", "{{baseUrl}}/login")
        val orderRequest = request("orders", "{{baseUrl}}/orders?token={{token}}")
        val startNode = start()
        val loginNode = node("login-node", loginRequest.id, ResponseExtractor(variable = "token", source = "data.token"))
        val orderNode = node("order-node", orderRequest.id)
        val endNode = end()

        val report = validate(
            scenario(
                startNode, loginNode, orderNode, endNode,
                edges = listOf(
                    edge(startNode, loginNode),
                    edge(loginNode, orderNode),
                    edge(orderNode, endNode)
                )
            )
        )

        assertTrue(report.isValid, report.problems.joinToString { it.message })
        assertTrue(report.canRun)
        assertTrue(report.problems.isEmpty(), report.problems.joinToString { it.message })
    }

    @Test
    fun `an empty scenario only warns about missing requests`() {
        val scenario = ScenarioConfig().apply { normalize() }

        val report = validate(scenario)

        assertTrue(report.isValid)
        assertFalse(report.canRun)
        assertTrue(report.warnings.any { it.message.contains("request node") })
        assertEquals(2, scenario.nodes.size)
    }

    @Test
    fun `a scenario without an end node is rejected`() {
        val startNode = start()
        val loginNode = node("login-node", request("login").id)
        val scenario = scenario(startNode, loginNode, edges = listOf(edge(startNode, loginNode)))

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("End node") })
    }

    @Test
    fun `a cycle is reported with the node names`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val b = node("b", request("b").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, b, endNode,
            edges = listOf(edge(startNode, a), edge(a, b), edge(b, a), edge(b, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("cycle") }, report.problems.joinToString { it.message })
    }

    @Test
    fun `a node that cannot be reached from start is reported`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val orphan = node("orphan", request("orphan").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, orphan, endNode,
            edges = listOf(edge(startNode, a), edge(a, endNode), edge(orphan, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("not reachable from Start") })
    }

    @Test
    fun `a node that cannot reach end is reported`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val deadEnd = node("dead-end", request("dead").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, deadEnd, endNode,
            edges = listOf(edge(startNode, a), edge(startNode, deadEnd), edge(a, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("cannot reach End") })
    }

    @Test
    fun `a deleted request is reported on its node`() {
        val startNode = start()
        val missing = ScenarioNode(
            id = "missing",
            kind = ScenarioNode.KIND_REQUEST,
            requestId = "deleted-request"
        )
        val endNode = end()
        val scenario = scenario(
            startNode, missing, endNode,
            edges = listOf(edge(startNode, missing), edge(missing, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        val problem = report.errors.first { it.message.contains("no longer exists") }
        assertEquals("missing", problem.nodeId)
    }

    @Test
    fun `a variable that no upstream node produces is an error`() {
        val startNode = start()
        val orderRequest = request("orders", "{{baseUrl}}/orders/{{orderId}}")
        val orderNode = node("order-node", orderRequest.id)
        val endNode = end()
        val scenario = scenario(
            startNode, orderNode, endNode,
            edges = listOf(edge(startNode, orderNode), edge(orderNode, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(
            report.errors.any { it.message.contains("orderId") && it.message.contains("no upstream node") },
            report.problems.joinToString { it.message }
        )
        assertEquals(setOf("orderId"), report.missingVariables)
    }

    @Test
    fun `a missing variable that a run parameter supplies is not reported`() {
        val startNode = start()
        val orderRequest = request("orders", "{{baseUrl}}/orders/{{orderId}}")
        val orderNode = node("order-node", orderRequest.id)
        val endNode = end()
        val scenario = scenario(
            startNode, orderNode, endNode,
            edges = listOf(edge(startNode, orderNode), edge(orderNode, endNode))
        )

        val report = validate(scenario, parameters = mapOf("orderId" to "42"))

        assertTrue(report.isValid, report.problems.joinToString { it.message })
        assertTrue(report.missingVariables.isEmpty())
    }

    @Test
    fun `a producer that is not upstream is reported as such`() {
        val startNode = start()
        val tokenRequest = request("login", "{{baseUrl}}/login")
        val orderRequest = request("orders", "{{baseUrl}}/orders?token={{token}}")
        val loginNode = node("login-node", tokenRequest.id, ResponseExtractor(variable = "token", source = "data.token"))
        val orderNode = node("order-node", orderRequest.id)
        val endNode = end()
        val scenario = scenario(
            startNode, loginNode, orderNode, endNode,
            edges = listOf(
                edge(startNode, loginNode),
                edge(startNode, orderNode),
                edge(loginNode, endNode),
                edge(orderNode, endNode)
            )
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(
            report.errors.any { it.message.contains("not upstream") },
            report.problems.joinToString { it.message }
        )
    }

    @Test
    fun `two parallel writers of the same variable produce a warning`() {
        val startNode = start()
        val a = node("a", request("a").id, ResponseExtractor(variable = "token", source = "data.token"))
        val b = node("b", request("b").id, ResponseExtractor(variable = "token", source = "data.token"))
        val endNode = end()
        val scenario = scenario(
            startNode, a, b, endNode,
            edges = listOf(edge(startNode, a), edge(startNode, b), edge(a, endNode), edge(b, endNode))
        )

        val report = validate(scenario)

        assertTrue(report.isValid, report.problems.joinToString { it.message })
        assertTrue(
            report.warnings.any { it.message.contains("parallel") },
            report.problems.joinToString { it.message }
        )
    }

    @Test
    fun `parameters shadowed by captures produce a warning`() {
        val startNode = start()
        val loginNode = node("login-node", request("login").id, ResponseExtractor(variable = "token", source = "data.token"))
        val endNode = end()
        val scenario = scenario(
            startNode, loginNode, endNode,
            edges = listOf(edge(startNode, loginNode), edge(loginNode, endNode))
        )

        val report = validate(scenario, parameters = mapOf("token" to "from-run"))

        assertTrue(
            report.warnings.any { it.message.contains("run parameter") && it.message.contains("captured") },
            report.problems.joinToString { it.message }
        )
    }

    @Test
    fun `duplicate dependencies are rejected`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, endNode,
            edges = listOf(edge(startNode, a), edge(a, endNode), edge(startNode, a))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("Duplicate dependency") })
    }

    @Test
    fun `an invalid expected status is rejected`() {
        val startNode = start()
        val a = ScenarioNode(
            id = "a",
            kind = ScenarioNode.KIND_REQUEST,
            requestId = request("a").id,
            expectStatus = "6xx"
        )
        val endNode = end()
        val scenario = scenario(
            startNode, a, endNode,
            edges = listOf(edge(startNode, a), edge(a, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("expect status") })
    }

    @Test
    fun `an invalid capture expression is rejected`() {
        val startNode = start()
        val a = node("a", request("a").id, ResponseExtractor(variable = "token", source = "data..token"))
        val endNode = end()
        val scenario = scenario(
            startNode, a, endNode,
            edges = listOf(edge(startNode, a), edge(a, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("invalid capture expression") })
    }

    @Test
    fun `baseUrl cannot be a run parameter`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, endNode,
            edges = listOf(edge(startNode, a), edge(a, endNode))
        )

        val report = validate(scenario, parameters = mapOf("baseUrl" to "http://evil"))

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("reserved") })
    }

    @Test
    fun `duplicate node ids stop the graph checks`() {
        val startNode = start()
        val first = node("same", request("a").id)
        val second = node("same", request("b").id)
        val endNode = end()
        val scenario = scenario(
            startNode, first, second, endNode,
            edges = listOf(edge(startNode, first), edge(first, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("share the id") })
    }

    @Test
    fun `start without outgoing dependencies is rejected when there are request nodes`() {
        val startNode = start()
        val a = node("a", request("a").id)
        val endNode = end()
        val scenario = scenario(
            startNode, a, endNode,
            edges = listOf(edge(a, endNode))
        )

        val report = validate(scenario)

        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.message.contains("Start has no outgoing") })
    }

    @Test
    fun `disabled rows and a disabled body do not require variables`() {
        val request = HttpRequestConfig(url = "{{baseUrl}}/x").apply {
            bodyType = BodyType.NONE.name
            body = "{{ignored}}"
            headers.add(KeyValue.of("X-Old", "{{ignored}}", enabled = false))
            params.add(KeyValue.of("page", "{{ignored}}", enabled = false))
        }

        val needed = ScenarioValidator.neededVariables(request)

        assertEquals(setOf("baseUrl"), needed)
    }
}
