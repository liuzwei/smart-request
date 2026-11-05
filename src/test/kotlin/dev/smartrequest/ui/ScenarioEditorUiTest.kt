package dev.smartrequest.ui

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.ScenarioRunState
import dev.smartrequest.scenario.ScenarioTransport
import dev.smartrequest.service.SmartRequestProjectService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class ScenarioEditorUiTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The editor tabs use empty scratch files in the IDE temp directory; the test VFS only
        // allows roots that a test registered explicitly.
        val scenarios = Path.of(PathManager.getTempPath(), "smart-request-scenarios").toString()
        VfsRootAccess.allowRootAccess(
            getTestRootDisposable(),
            scenarios,
            scenarios.replace('\\', '/')
        )
    }

    private fun service(): SmartRequestProjectService = project.service()

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    private fun addRequest(
        name: String,
        method: String = "GET",
        url: String = "{{baseUrl}}/$name",
        extractors: List<ResponseExtractor> = emptyList()
    ): HttpRequestConfig {
        val group = service().state().groups.first()
        val request = HttpRequestConfig(
            name = name,
            method = method,
            url = url,
            extractors = extractors.toMutableList()
        )
        group.requests.add(request)
        service().notifyChanged()
        return request
    }

    fun testOpeningTheSameScenarioTwiceReusesOneTab() {
        val scenario = onEdtGet { service().addScenario("Checkout") }
        val manager = FileEditorManager.getInstance(project)
        onEdt {
            ScenarioEditorService.getInstance(project).open(scenario.id)
            ScenarioEditorService.getInstance(project).open(scenario.id)
        }

        val files = manager.openFiles.filter {
            it.getUserData(ScenarioEditorService.SCENARIO_ID) == scenario.id
        }
        assertEquals(1, files.size)
        val file = files.single()
        assertEquals(ScenarioFileType.NAME, file.fileType.name)
        assertTrue(file.name, file.name.startsWith("Checkout"))
        assertEquals(1, manager.getEditors(file).size)

        onEdt { service().renameScenario(scenario.id, "Renamed") }
        assertTrue(file.name, file.name.startsWith("Renamed"))

        onEdt { service().removeScenario(scenario.id) }
        assertFalse(manager.openFiles.contains(file))
    }

    fun testStepsCanBeAddedAndCyclesAreRejected() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val login = onEdtGet { addRequest("Login", "POST") }
        val cart = onEdtGet { addRequest("Cart") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }

        onEdt {
            assertTrue(panel.addStepForTest(login.id))
            assertTrue(panel.addStepForTest(cart.id))
        }

        val steps = panel.stepsTableForTest()
        assertEquals(2, steps.rowCount())
        assertEquals("GET  Cart", steps.requestTextAt(1))
        assertTrue(steps.dependsTextAt(1), steps.dependsTextAt(1).contains("Login"))
        assertTrue(panel.validationTextForTest(), panel.validationTextForTest().startsWith("✓"))

        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
        val inspector = panel.inspectorForTest()
        onEdt {
            panel.selectNodeForTest(nodes.first().id)
            assertEquals(nodes.first().id, inspector.boundNodeId())
            assertFalse(inspector.isDependencyChecked(nodes.last().id))
            inspector.toggleDependency(nodes.last().id)
        }
        assertNotNull(inspector.message())
        assertTrue(inspector.message(), inspector.message()!!.contains("cycle"))
        assertFalse(inspector.isDependencyChecked(nodes.last().id))

        onEdt { inspector.setExpectStatus("2xx") }
        assertEquals("2xx", nodes.first().expectStatus)
        onEdt { inspector.setExpectStatus("nonsense") }
        assertEquals("nonsense", nodes.first().expectStatus)
        assertTrue(panel.validationTextForTest(), panel.validationTextForTest().contains("error"))

        onEdt { panel.dispose() }
    }

    fun testStepsAreSeededFromTheRequestsExtractRules() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val login = onEdtGet {
            addRequest(
                "Login",
                "POST",
                extractors = listOf(ResponseExtractor(variable = "token", source = "data.token"))
            )
        }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt { assertTrue(panel.addStepForTest(login.id)) }

        val node = panel.scenarioForTest()!!.nodes.single { it.isRequestNode() }
        assertEquals(1, node.outputs.size)
        assertEquals("token", node.outputs.first().variable)
        assertEquals(1, panel.inspectorForTest().outputsPanel().targetSizeForTest())

        onEdt { panel.dispose() }
    }

    fun testRunFeedsTheStepsAndTheRunPanel() {
        val scenario = onEdtGet { service().addScenario("Checkout") }
        val login = onEdtGet {
            addRequest(
                "Login",
                "POST",
                url = "{{baseUrl}}/login",
                extractors = listOf(ResponseExtractor(variable = "token", source = "data.token"))
            )
        }
        val order = onEdtGet { addRequest("Order", "POST", url = "{{baseUrl}}/orders?token={{token}}") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt {
            panel.addStepForTest(login.id)
            panel.addStepForTest(order.id)
            panel.testTransport = ScenarioTransport { prepared ->
                val body = if (prepared.url.contains("/login")) {
                    """{"token":"abc123"}"""
                } else {
                    """{"ok":true}"""
                }
                CompletableFuture.completedFuture(
                    HttpResponseData(
                        statusCode = 200,
                        statusText = "OK",
                        headers = listOf("Content-Type" to "application/json"),
                        body = body,
                        bodyBytes = body.length.toLong(),
                        durationMs = 7,
                        contentType = "application/json",
                        truncated = false,
                        textual = true
                    )
                )
            }
            assertTrue(panel.startRunForTest())
        }

        assertTrue(waitFor { panel.lastRunForTest() != null })
        val run = panel.lastRunForTest()!!
        assertEquals(ScenarioRunState.PASSED, run.state)
        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
        assertEquals("passed", panel.stepsTableForTest().statusTextFor(nodes[0].id))
        assertEquals("passed", panel.stepsTableForTest().statusTextFor(nodes[1].id))
        assertTrue(
            panel.stepsTableForTest().capturedTextFor(nodes[0].id),
            panel.stepsTableForTest().capturedTextFor(nodes[0].id).contains("token=abc123")
        )
        assertTrue(panel.runPanelForTest().summaryText().contains("2 passed"))
        assertTrue(panel.runPanelForTest().variablesText().contains("token=abc123"))

        onEdt { panel.dispose() }
    }

    fun testScenarioParametersTabFeedsTheDefaultsAndValidation() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val request = onEdtGet { addRequest("Cart", url = "{{baseUrl}}/cart?tenant={{scenarioParamXyz}}") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt { assertTrue(panel.addStepForTest(request.id)) }
        assertTrue(panel.validationTextForTest(), panel.validationTextForTest().contains("error"))

        onEdt {
            val parameters = panel.parametersPanelForTest()
            parameters.addRow()
            parameters.setRowValues(0, "scenarioParamXyz", "acme")
        }

        assertEquals(1, scenario.parameters.size)
        assertEquals("scenarioParamXyz", scenario.parameters.first().key)
        assertEquals("acme", scenario.parameters.first().value)
        assertTrue(panel.validationTextForTest(), panel.validationTextForTest().startsWith("✓"))

        onEdt { panel.dispose() }
    }

    fun testGraphViewSharesSelectionWithTheStepsTable() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val firstRequest = onEdtGet { addRequest("First") }
        val secondRequest = onEdtGet { addRequest("Second") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt {
            assertTrue(panel.addStepForTest(firstRequest.id))
            assertTrue(panel.addStepForTest(secondRequest.id))
            panel.showGraphForTest()
        }
        assertTrue(panel.isGraphViewForTest())
        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }

        onEdt { panel.graphCanvasForTest().selectNodeForTest(nodes[1].id) }
        assertEquals(nodes[1].id, panel.stepsTableForTest().selectedNodeId())
        assertEquals(nodes[1].id, panel.inspectorForTest().boundNodeId())

        onEdt { panel.showStepsForTest() }
        assertFalse(panel.isGraphViewForTest())
        assertEquals(nodes[1].id, panel.stepsTableForTest().selectedNodeId())

        onEdt {
            panel.showGraphForTest()
            panel.graphCanvasForTest().clearSelectionForTest()
        }
        assertNull(panel.stepsTableForTest().selectedNodeId())
        assertNull(panel.inspectorForTest().boundNodeId())

        onEdt { panel.dispose() }
    }

    fun testCanvasEditsAreUndoable() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val firstRequest = onEdtGet { addRequest("First") }
        val secondRequest = onEdtGet { addRequest("Second") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt {
            panel.addStepForTest(firstRequest.id)
            panel.addStepForTest(secondRequest.id)
            panel.showGraphForTest()
        }
        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
        val before = nodes.associate { it.id to (it.x to it.y) }

        onEdt { panel.graphCanvasForTest().autoLayoutForTest() }
        val laidOut = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
            .associate { it.id to (it.x to it.y) }
        assertFalse(before == laidOut)
        assertTrue(panel.canUndoForTest())

        onEdt { panel.undoForTest() }
        val restored = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
            .associate { it.id to (it.x to it.y) }
        assertEquals(before, restored)
        assertTrue(panel.canRedoForTest())

        onEdt { panel.redoForTest() }
        val redone = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
            .associate { it.id to (it.x to it.y) }
        assertEquals(laidOut, redone)

        onEdt { panel.dispose() }
    }

    fun testCanvasDependencyShowsUpInTheStepsTableAndCanBeUndone() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val firstRequest = onEdtGet { addRequest("First") }
        val secondRequest = onEdtGet { addRequest("Second") }
        val thirdRequest = onEdtGet { addRequest("Third") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt {
            panel.addStepForTest(firstRequest.id)
            panel.addStepForTest(secondRequest.id)
            panel.addStepForTest(thirdRequest.id)
            panel.showGraphForTest()
        }
        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }

        onEdt { assertTrue(panel.graphCanvasForTest().connectForTest(nodes[0].id, nodes[2].id)) }
        assertTrue(panel.stepsTableForTest().dependsTextAt(2).contains("First"))
        assertTrue(panel.stepsTableForTest().dependsTextAt(2).contains("Second"))

        onEdt { panel.undoForTest() }
        assertFalse(panel.stepsTableForTest().dependsTextAt(2).contains("First"))
        assertTrue(panel.stepsTableForTest().dependsTextAt(2).contains("Second"))

        onEdt { panel.dispose() }
    }

    fun testRunPaintsGraphStatesAndRunPanelSelectsNodes() {
        val scenario = onEdtGet { service().addScenario("Checkout") }
        val login = onEdtGet {
            addRequest(
                "Login",
                "POST",
                url = "{{baseUrl}}/login",
                extractors = listOf(ResponseExtractor(variable = "token", source = "data.token"))
            )
        }
        val order = onEdtGet { addRequest("Order", "POST", url = "{{baseUrl}}/orders?token={{token}}") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        onEdt {
            panel.addStepForTest(login.id)
            panel.addStepForTest(order.id)
            panel.showGraphForTest()
            panel.testTransport = ScenarioTransport { prepared ->
                val body = if (prepared.url.contains("/login")) {
                    """{"token":"abc123"}"""
                } else {
                    """{"ok":true}"""
                }
                CompletableFuture.completedFuture(
                    HttpResponseData(
                        statusCode = 200,
                        statusText = "OK",
                        headers = listOf("Content-Type" to "application/json"),
                        body = body,
                        bodyBytes = body.length.toLong(),
                        durationMs = 3,
                        contentType = "application/json",
                        truncated = false,
                        textual = true
                    )
                )
            }
            assertTrue(panel.startRunForTest())
        }
        assertTrue(waitFor { panel.lastRunForTest() != null })

        val nodes = panel.scenarioForTest()!!.nodes.filter { it.isRequestNode() }
        assertEquals(
            dev.smartrequest.scenario.NodeRunState.PASSED,
            panel.graphCanvasForTest().runStateForTest(nodes[0].id)
        )
        onEdt { panel.runPanelForTest().clickNodeForTest(nodes[1].id) }
        assertEquals(nodes[1].id, panel.stepsTableForTest().selectedNodeId())
        assertEquals(nodes[1].id, panel.graphCanvasForTest().selectedNodeForTest())

        onEdt { panel.dispose() }
    }

    fun testRunAgainstALocalServerUsesTheSharedClient() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { exchange ->
            respond(exchange, """{"token":"live"}""")
        }
        server.createContext("/order") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            respond(exchange, """{"ok":true,"query":"$query"}""")
        }
        server.start()
        val originalBaseUrl = onEdtGet { service().state().environments.first().baseUrl }
        onEdt {
            service().state().environments.first().baseUrl = "http://127.0.0.1:${server.address.port}"
            service().notifyChanged()
        }
        val scenario = onEdtGet { service().addScenario("Live") }
        val login = onEdtGet {
            addRequest(
                "Login",
                "POST",
                url = "{{baseUrl}}/login",
                extractors = listOf(ResponseExtractor(variable = "token", source = "data.token"))
            )
        }
        val order = onEdtGet { addRequest("Order", "GET", url = "{{baseUrl}}/order?token={{token}}") }
        val panel = onEdtGet { ScenarioEditorPanel(project, scenario.id) }
        try {
            onEdt {
                panel.addStepForTest(login.id)
                panel.addStepForTest(order.id)
                assertTrue(panel.startRunForTest())
            }
            assertTrue(waitFor { panel.lastRunForTest() != null })
            assertEquals(ScenarioRunState.PASSED, panel.lastRunForTest()!!.state)
            assertTrue(panel.runPanelForTest().variablesText().contains("token=live"))
            val requestStatuses = panel.lastRunForTest()!!.nodes.values
                .filter { it.method.isNotEmpty() }
                .map { it.statusCode }
            assertEquals(listOf(200, 200), requestStatuses)
        } finally {
            onEdt { panel.dispose() }
            server.stop(0)
            onEdt {
                service().state().environments.first().baseUrl = originalBaseUrl
                service().notifyChanged()
            }
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return true
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        return condition()
    }
}
