package dev.smartrequest.ui

import com.intellij.openapi.components.service
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.scenario.ScenarioGraph
import dev.smartrequest.service.SmartRequestProjectService
import dev.smartrequest.ui.dialogs.ScenarioRunDialog

class ScenarioRunDialogUiTest : BasePlatformTestCase() {

    private fun service(): SmartRequestProjectService = project.service()

    private fun <T> onEdtGet(action: () -> T): T = EdtTestUtil.runInEdtAndGet<T, RuntimeException> { action() }

    private fun onEdt(action: () -> Unit) = EdtTestUtil.runInEdtAndWait<RuntimeException> { action() }

    private fun addRequest(name: String, url: String): HttpRequestConfig {
        val request = HttpRequestConfig(name = name, url = url)
        service().state().groups.first().requests.add(request)
        service().notifyChanged()
        return request
    }

    fun testMissingVariablesArePrefilledAndBlockTheRunUntilFilled() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val request = onEdtGet { addRequest("Order", "{{baseUrl}}/orders/{{orderId}}") }
        onEdt {
            val node = ScenarioGraph.addRequestNode(scenario, request.id, afterNodeId = null)
            service().notifyChanged()
            assertNotNull(node)
        }
        val dialog = onEdtGet {
            ScenarioRunDialog(project, scenario, service(), service().activeEnvironment())
        }

        assertEquals(setOf("orderId"), dialog.missingVariableNames())
        assertTrue(dialog.missingVariableNames().contains("orderId"))
        assertTrue(dialog.statusText(), dialog.statusText().contains("fill in"))
        assertFalse(dialog.isRunEnabled())
        assertTrue(dialog.parameterNames().contains("orderId"))

        onEdt { dialog.setParameter("orderId", "42") }
        assertTrue(dialog.statusText(), dialog.isRunEnabled())
        assertEquals("42", dialog.parameterValue("orderId"))
        assertTrue(dialog.statusText().contains("Ready to run"))

        onEdt { dialog.disposeIfNeeded() }
    }

    fun testScenarioParametersArePrefilledAndCanBeChanged() {
        val scenario = onEdtGet {
            service().addScenario("Flow").apply {
                parameters.add(dev.smartrequest.model.KeyValue.of("tenant", "acme"))
            }
        }
        val request = onEdtGet { addRequest("Cart", "{{baseUrl}}/cart?tenant={{tenant}}&order={{orderId}}") }
        onEdt {
            ScenarioGraph.addRequestNode(scenario, request.id, afterNodeId = null)
            service().notifyChanged()
        }
        val dialog = onEdtGet { ScenarioRunDialog(project, scenario, service(), service().activeEnvironment()) }

        assertEquals("acme", dialog.parameterValue("tenant"))
        assertEquals(setOf("orderId"), dialog.missingVariableNames())
        assertFalse(dialog.isRunEnabled())

        onEdt { dialog.setParameter("tenant", "acme-eu") }
        assertEquals("acme-eu", dialog.parameterValue("tenant"))
        onEdt { dialog.setParameter("orderId", "7") }
        assertTrue(dialog.statusText(), dialog.isRunEnabled())
        assertEquals(mapOf("tenant" to "acme-eu", "orderId" to "7"), dialog.parameters())

        onEdt { dialog.disposeIfNeeded() }
    }

    fun testEnvironmentCanBeChosenInTheDialog() {
        val scenario = onEdtGet { service().addScenario("Flow") }
        val request = onEdtGet { addRequest("Cart", "{{baseUrl}}/cart/{{tenant}}") }
        val dev = onEdtGet {
            EnvironmentConfig(name = "dev", baseUrl = "http://dev:8080").also {
                service().state().environments.add(it)
                service().notifyChanged()
            }
        }
        onEdt {
            ScenarioGraph.addRequestNode(scenario, request.id, afterNodeId = null)
            service().notifyChanged()
        }
        val dialog = onEdtGet {
            ScenarioRunDialog(project, scenario, service(), service().activeEnvironment())
        }

        assertTrue(dialog.environmentNames().containsAll(listOf("local", "dev")))
        assertFalse(dialog.isRunEnabled())
        onEdt { dialog.selectEnvironment(dev.name) }
        assertEquals("dev", dialog.selectedEnvironmentName())
        // The variable is still missing in the new environment; filling it makes the flow runnable.
        onEdt { dialog.setParameter("tenant", "acme") }
        assertTrue(dialog.statusText(), dialog.isRunEnabled())
        assertEquals(dev, dialog.selectedEnvironment())

        onEdt { dialog.disposeIfNeeded() }
    }

    fun testAStructuralErrorKeepsTheRunDisabled() {
        val scenario = onEdtGet {
            ScenarioConfig(name = "Broken").apply {
                normalize()
                val node = dev.smartrequest.model.ScenarioNode(
                    kind = dev.smartrequest.model.ScenarioNode.KIND_REQUEST
                )
                nodes.add(node)
                ScenarioGraph.syncTerminals(this)
            }
        }
        val dialog = onEdtGet { ScenarioRunDialog(project, scenario, service(), service().activeEnvironment()) }

        assertFalse(dialog.isRunEnabled())
        assertTrue(dialog.statusText(), dialog.statusText().startsWith("Cannot run"))

        onEdt { dialog.disposeIfNeeded() }
    }
}
