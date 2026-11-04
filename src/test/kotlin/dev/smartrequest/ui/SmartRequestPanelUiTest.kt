package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.RequestHistoryEntry
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.dialogs.HistoryDialog

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

    fun testStateRoundTripsThroughXmlSerializer() {
        val state = SmartRequestState.createDefault().apply {
            globalHeaders.add(KeyValue.of("Authorization", "Bearer test"))
            globalParams.add(KeyValue.of("tenant", "acme"))
            groups.first().requests.first().headers.add(KeyValue.of("X-Test", "yes"))
            groups.first().requests.first().pathParams.add(KeyValue.of("id", "42"))
            groups.first().requests.first().extractors.add(
                ResponseExtractor(variable = "token", source = "data.token")
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

        assertEquals("Bearer test", restored.globalHeaders.first().value)
        assertEquals("tenant", restored.globalParams.first().key)
        assertEquals("X-Test", restored.groups.first().requests.first().headers.first().key)
        assertEquals("42", restored.groups.first().requests.first().pathParams.first().value)
        val restoredExtractor = restored.groups.first().requests.first().extractors.first()
        assertEquals("token", restoredExtractor.variable)
        assertEquals("data.token", restoredExtractor.source)
        assertEquals(true, restoredExtractor.enabled)
        assertEquals(1, restored.history.size)
        assertEquals(200, restored.history.first().statusCode)
        assertEquals("42", restored.history.first().request.pathParams.first().value)
        assertEquals(state.environments.first().id, restored.environments.first().id)
    }
}
