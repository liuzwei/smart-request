package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
}
