package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.FunctionDefinition
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.RequestHistoryEntry
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.dialogs.EnvironmentDialog
import dev.smartrequest.ui.dialogs.HistoryDialog
import java.awt.image.BufferedImage

/**
 * Smoke test that constructs the complete tool window in a real IntelliJ project. This catches
 * Swing/theme/API mistakes that plain unit tests cannot see.
 */
class SmartRequestPanelUiTest : BasePlatformTestCase() {

    fun testPanelCanBeCreatedAndDisposed() {
        val panel = EdtTestUtil.runInEdtAndGet<SmartRequestPanel, RuntimeException> {
            SmartRequestPanel(project)
        }
        assertNotNull(panel)
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            Disposer.dispose(panel)
        }
    }

    fun testScenarioListOpensTheScenarioInTheRightPane() {
        val service = project.service<SmartRequestProjectService>()
        val panel = EdtTestUtil.runInEdtAndGet<SmartRequestPanel, RuntimeException> {
            SmartRequestPanel(project)
        }
        val scenario = EdtTestUtil.runInEdtAndGet<ScenarioConfig, RuntimeException> {
            service.addScenario("Embedded")
        }
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            panel.scenarioListForTest().select(scenario.id)
        }

        val embedded = panel.scenarioPanelForTest()
        assertNotNull(embedded)
        assertEquals(scenario.id, embedded!!.scenarioIdForTest())
        assertEquals("scenario", panel.contentCardForTest())
        assertTrue(paintToImage(panel))

        val request = service.state().groups.first().requests.first()
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            project.messageBus
                .syncPublisher(dev.smartrequest.scenario.ScenarioTopics.OPEN_REQUEST)
                .openRequest(request.id)
        }
        assertEquals("request", panel.contentCardForTest())
        assertTrue(paintToImage(panel))

        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            Disposer.dispose(panel)
        }
    }

    private fun paintToImage(panel: SmartRequestPanel): Boolean {
        panel.setSize(1200, 800)
        panel.doLayout()
        val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB)
        panel.paint(image.createGraphics())
        return true
    }

    fun testPanelDetectsPathParametersOnOpen() {
        val service = project.service<SmartRequestProjectService>()
        val request = service.state().groups.first().requests.first().apply {
            url = "{{baseUrl}}/users/{id}"
        }
        val panel = EdtTestUtil.runInEdtAndGet<SmartRequestPanel, RuntimeException> {
            SmartRequestPanel(project)
        }
        assertEquals("id", request.pathParams.first().key)
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            Disposer.dispose(panel)
        }
    }

    fun testHistoryDialogCanBeCreated() {
        val service = project.service<SmartRequestProjectService>()
        service.addHistory(
            RequestHistoryEntry(
                timestamp = System.currentTimeMillis(),
                requestName = "Test",
                method = "GET",
                url = "http://localhost:8080/test",
                statusCode = 200,
                statusText = "OK",
                durationMs = 10,
                responseSize = 2,
                request = SmartRequestState.createDefault().groups.first().requests.first()
            )
        )
        val dialog = EdtTestUtil.runInEdtAndGet<HistoryDialog, RuntimeException> {
            HistoryDialog(project, service)
        }
        assertNotNull(dialog)
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            dialog.disposeIfNeeded()
        }
    }

    fun testEnvironmentDialogCanBeCreated() {
        val service = project.service<SmartRequestProjectService>()
        val (dialog, tabs) = EdtTestUtil.runInEdtAndGet<Pair<EnvironmentDialog, List<String>>, RuntimeException> {
            val created = EnvironmentDialog(project, service.state(), service)
            created to created.tabTitles()
        }
        assertNotNull(dialog)
        assertEquals(listOf("Variables", "Global Headers", "Global Params"), tabs)
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            dialog.disposeIfNeeded()
        }
    }

    fun testEnvironmentDialogOpensOnTheActiveEnvironment() {
        val service = project.service<SmartRequestProjectService>()
        val state = service.state()
        val dev = EnvironmentConfig(name = "dev", baseUrl = "http://dev:8080")
        state.environments.add(dev)
        state.activeEnvironmentId = dev.id

        val dialog = EdtTestUtil.runInEdtAndGet<EnvironmentDialog, RuntimeException> {
            EnvironmentDialog(project, state, service)
        }

        assertEquals("dev", dialog.selectedEnvironmentName())
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            dialog.disposeIfNeeded()
        }
    }

    fun testStateRoundTripsThroughXmlSerializer() {
        val state = SmartRequestState.createDefault().apply {
            environments.first().globalHeaders.add(KeyValue.of("Authorization", "Bearer test"))
            environments.first().globalParams.add(KeyValue.of("tenant", "acme"))
            groups.first().requests.first().headers.add(KeyValue.of("X-Test", "yes"))
            groups.first().requests.first().pathParams.add(KeyValue.of("id", "42"))
            groups.first().requests.first().useGlobalHeaders = false
            groups.first().requests.first().extractors.add(
                ResponseExtractor(variable = "token", source = "data.token")
            )
            functions.add(
                FunctionDefinition().apply {
                    name = "bearer"
                    parameters = mutableListOf("value")
                    expression = "concat('Bearer ', base64(value))"
                    description = "Authorization value"
                }
            )
            history.add(
                RequestHistoryEntry(
                    timestamp = 1_700_000_000_000,
                    requestName = "Health check",
                    method = "GET",
                    url = "http://localhost:8080/actuator/health",
                    statusCode = 200,
                    statusText = "OK",
                    durationMs = 42,
                    responseSize = 100,
                    request = groups.first().requests.first()
                )
            )
        }

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, SmartRequestState::class.java)

        val restoredEnvironment = restored.environments.first()
        assertEquals("Bearer test", restoredEnvironment.globalHeaders.first().value)
        assertEquals("tenant", restoredEnvironment.globalParams.first().key)
        assertEquals("X-Test", restored.groups.first().requests.first().headers.first().key)
        assertEquals("42", restored.groups.first().requests.first().pathParams.first().value)
        assertEquals(false, restored.groups.first().requests.first().useGlobalHeaders)
        assertEquals(true, restored.groups.first().requests.first().useGlobalParams)
        val restoredExtractor = restored.groups.first().requests.first().extractors.first()
        assertEquals("token", restoredExtractor.variable)
        assertEquals("data.token", restoredExtractor.source)
        assertEquals(true, restoredExtractor.enabled)
        assertEquals(1, restored.history.size)
        assertEquals(200, restored.history.first().statusCode)
        assertEquals("42", restored.history.first().request.pathParams.first().value)
        assertEquals(state.environments.first().id, restored.environments.first().id)
        val restoredFunction = restored.functions.single()
        assertEquals("bearer", restoredFunction.name)
        assertEquals(listOf("value"), restoredFunction.parameters)
        assertEquals("concat('Bearer ', base64(value))", restoredFunction.expression)
        assertEquals("Authorization value", restoredFunction.description)
    }

    fun testScenarioRoundTripsThroughXmlSerializer() {
        val start = ScenarioNode(kind = ScenarioNode.KIND_START, name = "Start", x = -220)
        val login = ScenarioNode(
            kind = ScenarioNode.KIND_REQUEST,
            requestId = "request-1",
            x = 0,
            y = 40,
            expectStatus = "2xx",
            timeoutMs = 5_000,
            retries = 2,
            outputs = mutableListOf(
                ResponseExtractor(variable = "token", source = "data.token", required = false)
            )
        )
        val end = ScenarioNode(kind = ScenarioNode.KIND_END, name = "End", x = 220)
        val state = SmartRequestState.createDefault().apply {
            scenarios.add(
                ScenarioConfig(
                    name = "Checkout",
                    notes = "Smoke test",
                    maxParallel = 3,
                    parameters = mutableListOf(KeyValue.of("tenant", "acme")),
                    nodes = mutableListOf(start, login, end),
                    edges = mutableListOf(
                        ScenarioEdge(from = start.id, to = login.id),
                        ScenarioEdge(from = login.id, to = end.id)
                    )
                )
            )
        }

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), SmartRequestState::class.java)

        val scenario = restored.scenarios.single()
        assertEquals("Checkout", scenario.name)
        assertEquals("Smoke test", scenario.notes)
        assertEquals(3, scenario.maxParallel)
        assertEquals("tenant", scenario.parameters.single().key)
        assertEquals(3, scenario.nodes.size)
        val restoredLogin = scenario.nodes.single { it.kind == ScenarioNode.KIND_REQUEST }
        assertEquals("request-1", restoredLogin.requestId)
        assertEquals(0, restoredLogin.x)
        assertEquals(40, restoredLogin.y)
        assertEquals(-220, scenario.nodes.single { it.kind == ScenarioNode.KIND_START }.x)
        assertEquals(220, scenario.nodes.single { it.kind == ScenarioNode.KIND_END }.x)
        assertEquals("2xx", restoredLogin.expectStatus)
        assertEquals(5_000, restoredLogin.timeoutMs)
        assertEquals(2, restoredLogin.retries)
        assertEquals("token", restoredLogin.outputs.single().variable)
        assertEquals("data.token", restoredLogin.outputs.single().source)
        assertEquals(false, restoredLogin.outputs.single().required)
        assertEquals(2, scenario.edges.size)
        assertTrue(scenario.edges.any { it.from == start.id && it.to == login.id })
    }
}
