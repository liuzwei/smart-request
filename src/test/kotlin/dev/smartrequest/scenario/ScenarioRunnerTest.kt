package dev.smartrequest.scenario

import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.http.HttpTransport
import dev.smartrequest.http.PreparedRequest
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class ScenarioRunnerTest {

    private val started = LinkedBlockingQueue<String>()
    private val pending = ConcurrentHashMap<String, CompletableFuture<HttpResponseData>>()
    private val prepared = CopyOnWriteArrayList<PreparedRequest>()
    private val requests = HashMap<String, HttpRequestConfig>()
    private val events = CopyOnWriteArrayList<ScenarioRunEvent>()

    private val transport = ScenarioTransport { request ->
        prepared.add(request)
        val future = CompletableFuture<HttpResponseData>()
        pending[request.url] = future
        started.add(request.url)
        future
    }

    private val requestSource = RequestSource { requests[it] }

    private fun request(id: String, url: String, method: String = "GET"): HttpRequestConfig {
        val request = HttpRequestConfig(id = id, name = id, method = method, url = url)
        requests[id] = request
        return request
    }

    private fun startNode() = ScenarioNode(kind = ScenarioNode.KIND_START, name = "Start", x = 0)

    private fun endNode() = ScenarioNode(kind = ScenarioNode.KIND_END, name = "End", x = 800)

    private fun requestNode(
        id: String,
        requestId: String,
        y: Int = 0,
        expectStatus: String = "",
        outputs: List<ResponseExtractor> = emptyList(),
        retries: Int = 0
    ): ScenarioNode = ScenarioNode(
        id = id,
        kind = ScenarioNode.KIND_REQUEST,
        requestId = requestId,
        x = 200,
        y = y,
        expectStatus = expectStatus,
        outputs = outputs.toMutableList(),
        retries = retries
    )

    private fun edge(from: ScenarioNode, to: ScenarioNode) = ScenarioEdge(from = from.id, to = to.id)

    private fun scenario(
        vararg nodes: ScenarioNode,
        edges: List<ScenarioEdge>,
        maxParallel: Int = 4
    ): ScenarioConfig = ScenarioConfig(
        name = "flow",
        maxParallel = maxParallel,
        nodes = nodes.toMutableList(),
        edges = edges.toMutableList()
    )

    private fun environment(variables: List<KeyValue> = emptyList()) = RunEnvironment(
        name = "test",
        baseUrl = "http://test",
        variables = variables,
        timeoutMs = 5_000
    )

    private fun run(
        scenario: ScenarioConfig,
        parameters: Map<String, String> = emptyMap(),
        environment: RunEnvironment = environment()
    ): ScenarioRunHandle = ScenarioRunner(transport, requestSource) { events.add(it) }
        .start(scenario, environment, parameters)

    private fun awaitStarted(timeoutMs: Long = 5_000): String =
        started.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("No request started within $timeoutMs ms")

    private fun assertNothingStarted(windowMs: Long = 400) {
        assertNull(started.poll(windowMs, TimeUnit.MILLISECONDS), "A node started too early")
    }

    private fun respond(url: String, status: Int = 200, body: String = "{}") {
        val future = pending[url] ?: error("No pending request for $url")
        future.complete(
            HttpResponseData(
                statusCode = status,
                statusText = HttpTransport.reasonPhrase(status),
                headers = emptyList(),
                body = body,
                bodyBytes = body.length.toLong(),
                durationMs = 5,
                contentType = "application/json",
                truncated = false,
                textual = true
            )
        )
    }

    private fun awaitResult(handle: ScenarioRunHandle): ScenarioRunResult =
        handle.result.get(10, TimeUnit.SECONDS)

    @Test
    fun `a linear chain passes captured values downstream`() {
        request("login", "http://test/login")
        request("cart", "http://test/cart?token={{token}}")
        val start = startNode()
        val login = requestNode("login-node", "login", outputs = listOf(ResponseExtractor(variable = "token", source = "data.token")))
        val cart = requestNode("cart-node", "cart")
        val end = endNode()

        val handle = run(
            scenario(
                start, login, cart, end,
                edges = listOf(edge(start, login), edge(login, cart), edge(cart, end))
            )
        )

        assertEquals("http://test/login", awaitStarted())
        respond("http://test/login", body = """{"token":"abc"}""")
        assertEquals("http://test/cart?token=abc", awaitStarted())
        respond("http://test/cart?token=abc")

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.PASSED, result.state)
        assertEquals(NodeRunState.PASSED, result.nodes.getValue("login-node").state)
        assertEquals(NodeRunState.PASSED, result.nodes.getValue("cart-node").state)
        assertEquals(NodeRunState.PASSED, result.nodes.getValue(end.id).state)
        assertEquals(listOf("token"), result.variables.map { it.name })
        assertEquals("abc", result.variables.single().value)
        assertEquals("login-node", result.variables.single().producerNodeId)
        assertEquals(2, events.count { it is ScenarioRunEvent.NodeStarted })
        assertTrue(events.first() is ScenarioRunEvent.RunStarted)
        assertTrue(events.last() is ScenarioRunEvent.RunFinished)
    }

    @Test
    fun `independent branches run in parallel`() {
        request("a", "http://test/a")
        request("b", "http://test/b")
        val start = startNode()
        val a = requestNode("a-node", "a", y = 0)
        val b = requestNode("b-node", "b", y = 100)
        val end = endNode()

        val handle = run(
            scenario(
                start, a, b, end,
                edges = listOf(edge(start, a), edge(start, b), edge(a, end), edge(b, end)),
                maxParallel = 2
            )
        )

        val urls = setOf(awaitStarted(), awaitStarted())
        assertEquals(setOf("http://test/a", "http://test/b"), urls)
        respond("http://test/a")
        respond("http://test/b")

        assertEquals(ScenarioRunState.PASSED, awaitResult(handle).state)
    }

    @Test
    fun `maxParallel one keeps the run sequential`() {
        request("a", "http://test/a")
        request("b", "http://test/b")
        val start = startNode()
        val a = requestNode("a-node", "a")
        val b = requestNode("b-node", "b")
        val end = endNode()

        val handle = run(
            scenario(
                start, a, b, end,
                edges = listOf(edge(start, a), edge(start, b), edge(a, end), edge(b, end)),
                maxParallel = 1
            )
        )

        assertEquals("http://test/a", awaitStarted())
        assertNothingStarted()
        respond("http://test/a")
        assertEquals("http://test/b", awaitStarted())
        respond("http://test/b")

        assertEquals(ScenarioRunState.PASSED, awaitResult(handle).state)
    }

    @Test
    fun `a join node waits for every predecessor`() {
        request("a", "http://test/a")
        request("b", "http://test/b")
        request("join", "http://test/join")
        val start = startNode()
        val a = requestNode("a-node", "a")
        val b = requestNode("b-node", "b")
        val join = requestNode("join-node", "join")
        val end = endNode()

        val handle = run(
            scenario(
                start, a, b, join, end,
                edges = listOf(
                    edge(start, a), edge(start, b),
                    edge(a, join), edge(b, join),
                    edge(join, end)
                ),
                maxParallel = 4
            )
        )

        val urls = setOf(awaitStarted(), awaitStarted())
        assertEquals(setOf("http://test/a", "http://test/b"), urls)
        respond("http://test/a")
        assertNothingStarted()
        respond("http://test/b")
        assertEquals("http://test/join", awaitStarted())
        respond("http://test/join")

        val result = awaitResult(handle)
        assertEquals(ScenarioRunState.PASSED, result.state)
        assertEquals(NodeRunState.PASSED, result.nodes.getValue("join-node").state)
    }

    @Test
    fun `the first failure cancels siblings and skips the rest`() {
        request("fail", "http://test/fail")
        request("slow", "http://test/slow")
        val start = startNode()
        val failing = requestNode("fail-node", "fail")
        val slow = requestNode("slow-node", "slow")
        val end = endNode()

        val handle = run(
            scenario(
                start, failing, slow, end,
                edges = listOf(edge(start, failing), edge(start, slow), edge(failing, end), edge(slow, end)),
                maxParallel = 2
            )
        )

        val urls = setOf(awaitStarted(), awaitStarted())
        assertEquals(setOf("http://test/fail", "http://test/slow"), urls)
        respond("http://test/fail", status = 500, body = """{"error":"boom"}""")

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals(NodeRunState.FAILED, result.nodes.getValue("fail-node").state)
        assertEquals(NodeRunState.CANCELLED, result.nodes.getValue("slow-node").state)
        assertEquals(NodeRunState.SKIPPED, result.nodes.getValue(end.id).state)
        assertTrue(pending.getValue("http://test/slow").isCancelled)
        assertNotNull(result.failure)
        assertTrue(result.failure!!.contains("Expected 200-399"), result.failure)
    }

    @Test
    fun `an expected error status passes the node`() {
        request("missing", "http://test/missing")
        request("after", "http://test/after")
        val start = startNode()
        val missing = requestNode("missing-node", "missing", expectStatus = "404")
        val after = requestNode("after-node", "after")
        val end = endNode()

        val handle = run(
            scenario(
                start, missing, after, end,
                edges = listOf(edge(start, missing), edge(missing, after), edge(after, end))
            )
        )

        assertEquals("http://test/missing", awaitStarted())
        respond("http://test/missing", status = 404, body = """{"error":"not found"}""")
        assertEquals("http://test/after", awaitStarted())
        respond("http://test/after")

        val result = awaitResult(handle)
        assertEquals(ScenarioRunState.PASSED, result.state)
        assertEquals(404, result.nodes.getValue("missing-node").statusCode)
    }

    @Test
    fun `an unexpected status fails with the actual code`() {
        request("broken", "http://test/broken")
        val start = startNode()
        val broken = requestNode("broken-node", "broken", expectStatus = "2xx")
        val end = endNode()

        val handle = run(
            scenario(start, broken, end, edges = listOf(edge(start, broken), edge(broken, end)))
        )

        assertEquals("http://test/broken", awaitStarted())
        respond("http://test/broken", status = 500)

        val result = awaitResult(handle)
        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals("Expected 2xx, got 500 Internal Server Error", result.nodes.getValue("broken-node").error)
    }

    @Test
    fun `a failed capture rule fails the node and leaks no value`() {
        request("login", "http://test/login")
        request("cart", "http://test/cart?token={{token}}")
        val start = startNode()
        val login = requestNode("login-node", "login", outputs = listOf(ResponseExtractor(variable = "token", source = "data.token")))
        val cart = requestNode("cart-node", "cart")
        val end = endNode()

        val handle = run(
            scenario(
                start, login, cart, end,
                edges = listOf(edge(start, login), edge(login, cart), edge(cart, end))
            )
        )

        assertEquals("http://test/login", awaitStarted())
        respond("http://test/login", body = """{"other":"value"}""")

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals(NodeRunState.FAILED, result.nodes.getValue("login-node").state)
        assertTrue(result.nodes.getValue("login-node").error!!.contains("Capture failed"))
        assertTrue(result.variables.isEmpty())
        assertEquals(NodeRunState.SKIPPED, result.nodes.getValue("cart-node").state)
    }

    @Test
    fun `run parameters override the environment and captures override parameters`() {
        request("login", "http://test/login?token={{token}}")
        request("cart", "http://test/cart?token={{token}}")
        val start = startNode()
        val login = requestNode("login-node", "login", outputs = listOf(ResponseExtractor(variable = "token", source = "data.token")))
        val cart = requestNode("cart-node", "cart")
        val end = endNode()

        val handle = run(
            scenario(
                start, login, cart, end,
                edges = listOf(edge(start, login), edge(login, cart), edge(cart, end))
            ),
            parameters = mapOf("token" to "from-parameter"),
            environment = environment(listOf(KeyValue.of("token", "from-environment")))
        )

        assertEquals("http://test/login?token=from-parameter", awaitStarted())
        respond("http://test/login?token=from-parameter", body = """{"token":"captured"}""")
        assertEquals("http://test/cart?token=captured", awaitStarted())
        respond("http://test/cart?token=captured")

        val result = awaitResult(handle)
        assertEquals(ScenarioRunState.PASSED, result.state)
        assertEquals("captured", result.variables.single().value)
    }

    @Test
    fun `a deleted request fails the run with a clear message`() {
        val start = startNode()
        val ghost = requestNode("ghost-node", "does-not-exist")
        val end = endNode()

        val handle = run(
            scenario(start, ghost, end, edges = listOf(edge(start, ghost), edge(ghost, end)))
        )

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals(NodeRunState.FAILED, result.nodes.getValue("ghost-node").state)
        assertTrue(result.nodes.getValue("ghost-node").error!!.contains("deleted"))
        assertEquals(NodeRunState.SKIPPED, result.nodes.getValue(end.id).state)
    }

    @Test
    fun `cancelling stops the run and marks in-flight nodes`() {
        request("slow", "http://test/slow")
        val start = startNode()
        val slow = requestNode("slow-node", "slow")
        val end = endNode()

        val handle = run(
            scenario(start, slow, end, edges = listOf(edge(start, slow), edge(slow, end)))
        )

        assertEquals("http://test/slow", awaitStarted())
        handle.cancel()

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.CANCELLED, result.state)
        assertEquals(NodeRunState.CANCELLED, result.nodes.getValue("slow-node").state)
        assertEquals(NodeRunState.SKIPPED, result.nodes.getValue(end.id).state)
        assertTrue(pending.getValue("http://test/slow").isCancelled)
    }

    @Test
    fun `a node timeout overrides the environment default`() {
        request("a", "http://test/a")
        val start = startNode()
        val node = requestNode("a-node", "a").apply { timeoutMs = 1_234 }
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/a", awaitStarted())
        assertEquals(1_234, prepared.last().timeoutMs)
        respond("http://test/a")
        assertEquals(ScenarioRunState.PASSED, awaitResult(handle).state)
    }

    @Test
    fun `a node without a timeout uses the environment timeout`() {
        request("a", "http://test/a")
        val start = startNode()
        val node = requestNode("a-node", "a")
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/a", awaitStarted())
        assertEquals(5_000, prepared.last().timeoutMs)
        respond("http://test/a")
        assertEquals(ScenarioRunState.PASSED, awaitResult(handle).state)
    }

    @Test
    fun `a node retries until it passes`() {
        request("flaky", "http://test/flaky")
        val start = startNode()
        val node = requestNode("flaky-node", "flaky", retries = 2)
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/flaky", awaitStarted())
        respond("http://test/flaky", status = 500)
        assertEquals("http://test/flaky", awaitStarted())
        respond("http://test/flaky", status = 503)
        assertEquals("http://test/flaky", awaitStarted())
        respond("http://test/flaky", status = 200)

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.PASSED, result.state)
        assertEquals(NodeRunState.PASSED, result.nodes.getValue(node.id).state)
        assertEquals(200, result.nodes.getValue(node.id).statusCode)
        assertEquals(3, prepared.count { it.url == "http://test/flaky" })
    }

    @Test
    fun `retries are exhausted with the attempt count in the error`() {
        request("flaky", "http://test/flaky")
        val start = startNode()
        val node = requestNode("flaky-node", "flaky", retries = 1)
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/flaky", awaitStarted())
        respond("http://test/flaky", status = 500)
        assertEquals("http://test/flaky", awaitStarted())
        respond("http://test/flaky", status = 500)

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        val error = result.nodes.getValue(node.id).error.orEmpty()
        assertTrue(error.contains("after 2 attempts"), error)
        assertEquals(2, prepared.count { it.url == "http://test/flaky" })
    }

    @Test
    fun `an optional capture failure does not fail the node`() {
        request("login", "http://test/login")
        val start = startNode()
        val node = requestNode(
            "login-node",
            "login",
            outputs = listOf(ResponseExtractor(variable = "token", source = "data.token", required = false))
        )
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/login", awaitStarted())
        respond("http://test/login", body = """{"ok":true}""")

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.PASSED, result.state)
        val nodeResult = result.nodes.getValue(node.id)
        assertEquals(NodeRunState.PASSED, nodeResult.state)
        assertFalse(nodeResult.outputs.single().successful)
        assertFalse(result.variables.any { it.name == "token" })
        assertEquals(1, prepared.size)
    }

    @Test
    fun `a required capture failure still fails the node`() {
        request("login", "http://test/login")
        val start = startNode()
        val node = requestNode(
            "login-node",
            "login",
            outputs = listOf(ResponseExtractor(variable = "token", source = "data.token"))
        )
        val end = endNode()

        val handle = run(
            scenario(start, node, end, edges = listOf(edge(start, node), edge(node, end)))
        )

        assertEquals("http://test/login", awaitStarted())
        respond("http://test/login", body = """{"ok":true}""")

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals(NodeRunState.FAILED, result.nodes.getValue(node.id).state)
        assertTrue(result.nodes.getValue(node.id).error.orEmpty().contains("Capture failed"))
    }

    @Test
    fun `all node results are present even when the run fails early`() {        request("a", "http://test/a")
        request("b", "http://test/b")
        val start = startNode()
        val a = requestNode("a-node", "a")
        val b = requestNode("b-node", "b")
        val end = endNode()

        val handle = run(
            scenario(
                start, a, b, end,
                edges = listOf(edge(start, a), edge(start, b), edge(a, end), edge(b, end)),
                maxParallel = 1
            )
        )

        assertEquals("http://test/a", awaitStarted())
        respond("http://test/a", status = 500)

        val result = awaitResult(handle)

        assertEquals(ScenarioRunState.FAILED, result.state)
        assertEquals(4, result.nodes.size)
        assertEquals(NodeRunState.SKIPPED, result.nodes.getValue("b-node").state)
        assertFalse(result.nodes.values.any { it.state == NodeRunState.RUNNING })
    }
}
