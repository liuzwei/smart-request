package dev.smartrequest.ui

import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.extract.ExtractedValue
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.CapturedVariable
import dev.smartrequest.scenario.NodeRunState
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.scenario.ScenarioNodeResult
import dev.smartrequest.scenario.ScenarioRunResult
import dev.smartrequest.scenario.ScenarioRunState

class ScenarioRunPanelUiTest : BasePlatformTestCase() {

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    fun testResultsAreShownLiveAndInTheFinalSummary() {
        val requests = mapOf(
            "r1" to HttpRequestConfig(id = "r1", name = "Login", method = "POST", url = "{{baseUrl}}/login"),
            "r2" to HttpRequestConfig(id = "r2", name = "Order", method = "POST", url = "{{baseUrl}}/orders")
        )
        val scenario = ScenarioConfig(name = "Checkout").apply {
            normalize()
            ScenarioGraph.addRequestNode(this, "r1", afterNodeId = null)
            ScenarioGraph.addRequestNode(this, "r2")
        }
        val nodes = ScenarioGraph.requestNodes(scenario)
        val panel = onEdtGet { ScenarioRunPanel({ id -> requests[id] }, {}) }

        onEdt { panel.startRun(scenario) }
        assertEquals(2, panel.rowCount())
        assertTrue(panel.isCancelVisible())
        assertTrue(panel.summaryText(), panel.summaryText().startsWith("Running"))

        onEdt {
            panel.setNodeState(nodes[0].id, NodeRunState.RUNNING)
            panel.updateNode(
                ScenarioNodeResult(
                    nodeId = nodes[0].id,
                    name = "Login",
                    state = NodeRunState.PASSED,
                    method = "POST",
                    statusCode = 200,
                    durationMs = 12,
                    outputs = listOf(
                        ExtractedValue(
                            extractorId = "e1",
                            variable = "token",
                            source = "data.token",
                            value = "abc"
                        )
                    )
                )
            )
            panel.updateNode(
                ScenarioNodeResult(
                    nodeId = nodes[1].id,
                    name = "Order",
                    state = NodeRunState.RUNNING,
                    method = "POST",
                    statusCode = 200,
                    durationMs = 5
                )
            )
        }
        assertEquals("passed", panel.statusFor(nodes[0].id))
        assertEquals("running", panel.statusFor(nodes[1].id))

        val finished = ScenarioRunResult(
            scenarioId = scenario.id,
            state = ScenarioRunState.FAILED,
            startedAtMs = 0,
            durationMs = 42,
            nodes = mapOf(
                nodes[0].id to ScenarioNodeResult(
                    nodes[0].id, "Login", NodeRunState.PASSED, "POST", statusCode = 200, durationMs = 12
                ),
                nodes[1].id to ScenarioNodeResult(
                    nodes[1].id,
                    "Order",
                    NodeRunState.FAILED,
                    "POST",
                    statusCode = 500,
                    durationMs = 30,
                    error = "Expected 2xx, got 500 Internal Server Error"
                )
            ),
            variables = listOf(CapturedVariable("token", "abc", nodes[0].id)),
            failure = "Expected 2xx, got 500 Internal Server Error"
        )
        onEdt { panel.finish(finished) }

        assertFalse(panel.isCancelVisible())
        assertEquals("failed", panel.statusFor(nodes[1].id))
        assertTrue(panel.summaryText(), panel.summaryText().contains("1 passed, 1 failed"))
        assertEquals("Expected 2xx, got 500 Internal Server Error", panel.failureText())
        assertEquals("token=abc", panel.variablesText())
        assertEquals("Login", panel.variableProducer("token"))

        onEdt { panel.reset() }
        assertEquals(0, panel.rowCount())
    }

    fun testSkippedNodesAppearInTheSummary() {
        val request = HttpRequestConfig(id = "r1", name = "Only", method = "GET")
        val scenario = ScenarioConfig(name = "Flow").apply {
            normalize()
            ScenarioGraph.addRequestNode(this, "r1", afterNodeId = null)
        }
        val node = ScenarioGraph.requestNodes(scenario).single()
        val panel = onEdtGet { ScenarioRunPanel({ request }, {}) }
        onEdt { panel.startRun(scenario) }
        val finished = ScenarioRunResult(
            scenarioId = scenario.id,
            state = ScenarioRunState.CANCELLED,
            startedAtMs = 0,
            durationMs = 10,
            nodes = mapOf(
                node.id to ScenarioNodeResult(node.id, "Only", NodeRunState.SKIPPED, "GET")
            ),
            variables = emptyList(),
            failure = "Run cancelled"
        )
        onEdt { panel.finish(finished) }

        assertTrue(panel.summaryText(), panel.summaryText().contains("0 passed"))
        assertTrue(panel.summaryText(), panel.summaryText().contains("1 skipped"))
        assertEquals("skipped", panel.statusFor(node.id))
    }
}
