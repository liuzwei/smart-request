package dev.smartrequest.service

import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.scenario.ScenarioGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioServiceTest {

    @Test
    fun `adding a scenario creates one start and one end and notifies once`() {
        val service = SmartRequestProjectService()
        var changes = 0
        service.addChangeListener { changes++ }

        val scenario = service.addScenario("Checkout")

        assertEquals(1, changes)
        assertEquals("Checkout", scenario.name)
        assertEquals(2, scenario.nodes.size)
        assertNotNull(ScenarioGraph.start(scenario))
        assertNotNull(ScenarioGraph.end(scenario))
        assertEquals(listOf(scenario), service.scenarios())
    }

    @Test
    fun `scenario names stay unique`() {
        val service = SmartRequestProjectService()

        val first = service.addScenario("Flow")
        val second = service.addScenario("Flow")

        assertEquals("Flow", first.name)
        assertEquals("Flow 2", second.name)
        assertEquals("Flow 3", service.uniqueScenarioName("Flow"))
        assertEquals("New Scenario", service.uniqueScenarioName("   "))
    }

    @Test
    fun `renaming trims and avoids another scenario's name`() {
        val service = SmartRequestProjectService()
        val first = service.addScenario("First")
        val second = service.addScenario("Second")

        assertFalse(service.renameScenario(first.id, "   "))
        assertFalse(service.renameScenario("missing", "Name"))
        assertTrue(service.renameScenario(first.id, "Second"))
        assertEquals("Second 2", first.name)
        assertEquals("Second", second.name)
        assertTrue(service.renameScenario(second.id, "Renamed"))
        assertEquals("Renamed", second.name)
        assertFalse(service.renameScenario(second.id, "Renamed"))
    }

    @Test
    fun `duplicating copies the flow with fresh ids and remapped edges`() {
        val service = SmartRequestProjectService()
        val original = service.addScenario("Checkout")
        val first = ScenarioGraph.addRequestNode(original, "request-1")
        val second = ScenarioGraph.addRequestNode(original, "request-2")
        first.outputs.add(ResponseExtractor(variable = "token", source = "data.token"))

        val copy = service.duplicateScenario(original.id)

        assertNotNull(copy)
        copy!!
        assertNotEquals(original.id, copy.id)
        assertEquals("Checkout copy", copy.name)
        assertEquals(2, service.scenarios().size)
        assertEquals(original, service.scenarios()[0])
        assertEquals(copy, service.scenarios()[1])
        assertEquals(2, ScenarioGraph.requestNodes(copy).size)
        val copiedFirst = ScenarioGraph.requestNodes(copy).first()
        val copiedSecond = ScenarioGraph.requestNodes(copy).last()
        assertNotEquals(first.id, copiedFirst.id)
        assertNotEquals(second.id, copiedSecond.id)
        assertTrue(copiedFirst.id !in original.nodes.map { it.id })
        assertEquals(listOf(copiedFirst.id), ScenarioGraph.requestPredecessors(copy, copiedSecond.id))
        assertEquals(1, copiedFirst.outputs.size)
        assertEquals("token", copiedFirst.outputs.first().variable)
        assertNotEquals(first.outputs.first().id, copiedFirst.outputs.first().id)
        assertEquals(2, ScenarioGraph.requestNodes(original).size)
    }

    @Test
    fun `deleting a scenario removes it and notifies`() {
        val service = SmartRequestProjectService()
        val scenario = service.addScenario("Flow")
        var changes = 0
        service.addChangeListener { changes++ }

        service.removeScenario(scenario.id)
        service.removeScenario("missing")

        assertEquals(1, changes)
        assertTrue(service.scenarios().isEmpty())
        assertNull(service.scenario(scenario.id))
    }

    @Test
    fun `scenarioOf finds the owner of a node`() {
        val service = SmartRequestProjectService()
        val scenario = service.addScenario("Flow")
        val node = ScenarioGraph.addRequestNode(scenario, "request-1")
        val other = service.addScenario("Other")

        assertEquals(scenario, service.scenarioOf(node.id))
        assertEquals(other, service.scenarioOf(other.nodes.first().id))
        assertNull(service.scenarioOf("missing"))
    }

    @Test
    fun `a scenario created by the service is normalized`() {
        val service = SmartRequestProjectService()
        val scenario = ScenarioConfig(
            name = "Flow",
            maxParallel = 99,
            nodes = mutableListOf(
                ScenarioNode(kind = ScenarioNode.KIND_REQUEST, requestId = "request-1"),
                ScenarioNode(kind = ScenarioNode.KIND_REQUEST, requestId = "request-2")
            )
        )

        service.state().scenarios.add(scenario)
        service.normalizeAndNotify()

        assertEquals(ScenarioConfig.MAX_MAX_PARALLEL, scenario.maxParallel)
        assertEquals(4, scenario.nodes.size)
        assertEquals(2, ScenarioGraph.requestNodes(scenario).size)
        assertNotNull(ScenarioGraph.start(scenario))
        assertNotNull(ScenarioGraph.end(scenario))
    }
}
