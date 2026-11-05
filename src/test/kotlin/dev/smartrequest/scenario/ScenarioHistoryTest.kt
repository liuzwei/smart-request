package dev.smartrequest.scenario

import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ScenarioConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioHistoryTest {

    private fun scenario(): ScenarioConfig = ScenarioConfig(name = "History").apply { normalize() }

    @Test
    fun `undo and redo restore nodes and edges`() {
        val scenario = scenario()
        val history = ScenarioHistory()

        history.record(scenario)
        val node = ScenarioGraph.addRequestNode(scenario, "request-1")
        assertEquals(3, scenario.nodes.size)
        assertTrue(history.canUndo)

        assertTrue(history.undo(scenario))
        assertEquals(2, scenario.nodes.size)
        assertTrue(scenario.edges.isEmpty())
        assertTrue(history.canRedo)

        assertTrue(history.redo(scenario))
        assertEquals(3, scenario.nodes.size)
        assertEquals(node.id, scenario.nodes.single { it.isRequestNode() }.id)
        assertTrue(scenario.edges.any { it.to == node.id })
    }

    @Test
    fun `undo restores node positions and captures`() {
        val scenario = scenario()
        val node = ScenarioGraph.addRequestNode(scenario, "request-1")
        node.x = 40
        node.y = 20
        node.outputs.add(dev.smartrequest.model.ResponseExtractor(variable = "token", source = "data.token"))
        val history = ScenarioHistory()

        history.record(scenario)
        node.x = 500
        node.y = 300
        node.outputs.clear()

        history.undo(scenario)

        val restored = scenario.nodes.single { it.isRequestNode() }
        assertEquals(40, restored.x)
        assertEquals(20, restored.y)
        assertEquals(1, restored.outputs.size)
        assertEquals("token", restored.outputs.first().variable)
    }

    @Test
    fun `a rejected edit can be discarded`() {
        val scenario = scenario()
        val history = ScenarioHistory()
        val before = scenario.edges.size

        history.record(scenario)
        history.discardLast()

        assertFalse(history.canUndo)
        assertFalse(history.undo(scenario))
        assertEquals(before, scenario.edges.size)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val scenario = scenario()
        val history = ScenarioHistory()
        history.record(scenario)
        ScenarioGraph.addRequestNode(scenario, "request-1")
        history.undo(scenario)
        assertTrue(history.canRedo)

        history.record(scenario)
        assertFalse(history.canRedo)
    }

    @Test
    fun `the history keeps at most the configured number of steps`() {
        val scenario = scenario()
        val history = ScenarioHistory(limit = 3)
        repeat(5) {
            history.record(scenario)
            ScenarioGraph.addRequestNode(scenario, "request-$it", afterNodeId = null)
        }

        assertEquals(3, history.undoSize)
        repeat(3) { assertTrue(history.undo(scenario)) }
        assertFalse(history.undo(scenario))
    }

    @Test
    fun `undo does not change the scenario name or parameters`() {
        val scenario = scenario().apply {
            name = "Checkout"
            parameters.add(KeyValue.of("tenant", "acme"))
        }
        val history = ScenarioHistory()
        history.record(scenario)
        ScenarioGraph.addRequestNode(scenario, "request-1")

        history.undo(scenario)

        assertEquals("Checkout", scenario.name)
        assertEquals(1, scenario.parameters.size)
        assertEquals("tenant", scenario.parameters.first().key)
    }

    @Test
    fun `snapshots are independent copies`() {
        val scenario = scenario()
        val node = ScenarioGraph.addRequestNode(scenario, "request-1")
        val history = ScenarioHistory()
        history.record(scenario)

        node.x = 999
        history.undo(scenario)
        val restored = scenario.nodes.single { it.isRequestNode() }
        assertNotEquals(999, restored.x)
        assertNotEquals(node, restored)
    }
}
