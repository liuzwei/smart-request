package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.service.SmartRequestProjectService

class ScenarioListPanelUiTest : BasePlatformTestCase() {

    private fun service(): SmartRequestProjectService = project.service()

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    fun testScenariosCanBeCreatedFilteredDuplicatedAndDeleted() {
        onEdt {
            // The test project may have scenarios left over from an earlier test method.
            service().state().scenarios.clear()
            service().notifyChanged()
        }
        val panel = onEdtGet { ScenarioListPanel(project) }

        val flow = onEdtGet { panel.createForTest() }
        assertEquals(1, panel.scenarioCount())
        assertTrue(panel.isDeleteEnabled())

        val second = onEdtGet { panel.createForTest() }
        assertEquals(2, panel.scenarioCount())
        assertTrue(panel.names().contains("New Scenario 2"))

        onEdt { panel.search("2") }
        assertEquals(listOf("New Scenario 2"), panel.names())
        onEdt { panel.search("") }
        assertEquals(2, panel.scenarioCount())

        val copy = onEdtGet { panel.duplicateForTest(flow.id) }
        assertNotNull(copy)
        assertEquals(3, panel.scenarioCount())
        assertTrue(panel.names().contains("New Scenario copy"))

        onEdt { panel.deleteForTest(second.id) }
        assertEquals(2, panel.scenarioCount())
        assertNull(service().scenario(second.id))

        onEdt { panel.dispose() }
    }

    fun testSelectionEnablesTheActionButtons() {
        val panel = onEdtGet { ScenarioListPanel(project) }
        val scenario = onEdtGet { service().addScenario("Flow") }
        onEdt {
            assertTrue(panel.select(scenario.id))
        }
        assertTrue(panel.isDeleteEnabled())

        onEdt { panel.dispose() }
    }

    fun testScenarioCanBeExportedAndImportedAsJson() {
        onEdt {
            service().state().scenarios.clear()
            service().notifyChanged()
        }
        val panel = onEdtGet { ScenarioListPanel(project) }
        val scenario = onEdtGet { service().addScenario("Shared") }
        val request = onEdtGet {
            HttpRequestConfig(name = "Login", method = "POST", url = "{{baseUrl}}/login").also {
                service().state().groups.first().requests.add(it)
            }
        }
        onEdt {
            val node = ScenarioGraph.addRequestNode(scenario, request.id, afterNodeId = null)
            node.expectStatus = "2xx"
            node.retries = 1
            service().notifyChanged()
        }

        val json = onEdtGet { panel.exportForTest(scenario.id) }
        assertNotNull(json)
        assertTrue(json!!.contains("\"format\": \"smart-request-scenario\""))
        assertTrue(json.contains(request.name))
        assertTrue(json.contains("\"expectStatus\": \"2xx\""))

        onEdt { service().removeScenario(scenario.id) }
        val imported = onEdtGet { panel.importForTest(json) }

        assertEquals("Shared", imported.name)
        val node = ScenarioGraph.requestNodes(imported).single()
        assertNotNull(node.requestId)
        assertFalse(node.requestId == request.id)
        assertEquals("2xx", node.expectStatus)
        assertEquals(1, node.retries)
        assertTrue(panel.names().contains("Shared"))

        onEdt { panel.dispose() }
    }
}
