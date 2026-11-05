package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioGraphTest {

    private fun scenario(): ScenarioConfig = ScenarioConfig(name = "Test").apply { normalize() }

    private fun ScenarioConfig.requestIds(): List<String> =
        ScenarioGraph.requestNodes(this).map { it.id }

    @Test
    fun `a normalized scenario starts with one start and one end`() {
        val scenario = scenario()

        assertNotNull(ScenarioGraph.start(scenario))
        assertNotNull(ScenarioGraph.end(scenario))
        assertEquals(2, scenario.nodes.size)
        assertTrue(scenario.edges.isEmpty())
        assertEquals(-ScenarioConfig.TERMINAL_SPACING, ScenarioGraph.start(scenario)!!.x)
        assertEquals(0, ScenarioGraph.end(scenario)!!.x)
    }

    @Test
    fun `adding a step appends it after the previous one and keeps start and end valid`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2")

        assertEquals(listOf(first.id, second.id), scenario.requestIds())
        assertEquals(listOf<String>(), ScenarioGraph.requestPredecessors(scenario, first.id))
        assertEquals(listOf(first.id), ScenarioGraph.requestPredecessors(scenario, second.id))
        assertEquals(0, first.x)
        assertEquals(ScenarioConfig.TERMINAL_SPACING, second.x)
        assertTrue(scenario.edges.any { it.from == ScenarioGraph.start(scenario)!!.id && it.to == first.id })
        assertTrue(scenario.edges.any { it.from == second.id && it.to == ScenarioGraph.end(scenario)!!.id })
        assertFalse(scenario.edges.any { it.from == first.id && it.to == ScenarioGraph.end(scenario)!!.id })
        assertEquals(listOf(first.id, second.id), ScenarioGraph.displayOrder(scenario).map { it.id })
    }

    @Test
    fun `a step added without a predecessor runs in parallel`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2", afterNodeId = null)

        assertEquals(emptyList<String>(), ScenarioGraph.requestPredecessors(scenario, second.id))
        val startId = ScenarioGraph.start(scenario)!!.id
        assertEquals(listOf(startId), scenario.edges.filter { it.to == first.id }.map { it.from })
        assertEquals(listOf(startId), scenario.edges.filter { it.to == second.id }.map { it.from })
        val endId = ScenarioGraph.end(scenario)!!.id
        assertEquals(2, scenario.edges.count { it.to == endId })
        assertEquals(listOf(first.id, second.id), ScenarioGraph.displayOrder(scenario).map { it.id })
    }

    @Test
    fun `setting dependencies rewires start and end edges`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2", afterNodeId = null)
        val third = ScenarioGraph.addRequestNode(scenario, "request-3", afterNodeId = null)

        val result = ScenarioGraph.setDependencies(scenario, third.id, listOf(first.id, second.id))

        assertTrue(result.ok, result.error)
        assertEquals(listOf(first.id, second.id), ScenarioGraph.requestPredecessors(scenario, third.id))
        val endId = ScenarioGraph.end(scenario)!!.id
        assertEquals(listOf(third.id), scenario.edges.filter { it.to == endId }.map { it.from })
        assertEquals(listOf(third.id), ScenarioGraph.displayOrder(scenario).map { it.id }.takeLast(1))
    }

    @Test
    fun `a dependency that would close a cycle is rejected without changing the graph`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2")
        val before = scenario.edges.map { it.from to it.to }

        val result = ScenarioGraph.setDependencies(scenario, first.id, listOf(second.id))

        assertFalse(result.ok)
        assertNotNull(result.error)
        assertEquals(before, scenario.edges.map { it.from to it.to })
    }

    @Test
    fun `a self dependency is ignored`() {
        val scenario = scenario()
        val node = ScenarioGraph.addRequestNode(scenario, "request-1")

        val result = ScenarioGraph.setDependencies(scenario, node.id, listOf(node.id))

        assertTrue(result.ok)
        assertEquals(emptyList<String>(), ScenarioGraph.requestPredecessors(scenario, node.id))
    }

    @Test
    fun `add and remove dependency update the terminals`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2", afterNodeId = null)

        assertTrue(ScenarioGraph.addDependency(scenario, first.id, second.id).ok)
        assertEquals(listOf(first.id), ScenarioGraph.requestPredecessors(scenario, second.id))

        assertTrue(ScenarioGraph.removeDependency(scenario, first.id, second.id).ok)
        assertEquals(emptyList<String>(), ScenarioGraph.requestPredecessors(scenario, second.id))
        val startId = ScenarioGraph.start(scenario)!!.id
        assertTrue(scenario.edges.any { it.from == startId && it.to == second.id })
    }

    @Test
    fun `removing a step reconnects its neighbours`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2")
        val third = ScenarioGraph.addRequestNode(scenario, "request-3")

        assertTrue(ScenarioGraph.removeNode(scenario, second.id))

        assertEquals(listOf(first.id, third.id), scenario.requestIds())
        assertTrue(scenario.edges.any { it.from == first.id && it.to == third.id })
        assertTrue(scenario.edges.none { it.from == second.id || it.to == second.id })
        assertEquals(listOf(first.id, third.id), ScenarioGraph.displayOrder(scenario).map { it.id })
    }

    @Test
    fun `start and end nodes cannot be removed`() {
        val scenario = scenario()
        val start = ScenarioGraph.start(scenario)!!
        val end = ScenarioGraph.end(scenario)!!

        assertFalse(ScenarioGraph.removeNode(scenario, start.id))
        assertFalse(ScenarioGraph.removeNode(scenario, end.id))
        assertEquals(2, scenario.nodes.size)
    }

    @Test
    fun `display order handles joins and independent branches`() {
        val scenario = scenario()
        val root = ScenarioGraph.addRequestNode(scenario, "root")
        val left = ScenarioGraph.addRequestNode(scenario, "left", afterNodeId = null)
        val right = ScenarioGraph.addRequestNode(scenario, "right", afterNodeId = null)
        val join = ScenarioGraph.addRequestNode(scenario, "join", afterNodeId = null)
        ScenarioGraph.setDependencies(scenario, left.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, right.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, join.id, listOf(left.id, right.id))

        val order = ScenarioGraph.displayOrder(scenario).map { it.id }

        assertEquals(listOf(root.id, left.id, right.id, join.id), order)
        assertEquals(setOf(root.id, left.id, right.id), ScenarioGraph.ancestors(scenario, join.id))
        assertEquals(setOf(left.id, right.id, join.id), ScenarioGraph.descendants(scenario, root.id))
    }

    @Test
    fun `grid coordinates leave room for new steps`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2")

        assertEquals(-ScenarioConfig.TERMINAL_SPACING, ScenarioGraph.start(scenario)!!.x)
        assertEquals(0, first.x)
        assertEquals(ScenarioConfig.TERMINAL_SPACING, second.x)
        assertEquals(second.x + ScenarioConfig.TERMINAL_SPACING, ScenarioGraph.end(scenario)!!.x)
        assertEquals(0, second.y)
        assertEquals(0, ScenarioGraph.end(scenario)!!.y)
    }

    @Test
    fun `a duplicated dependency is not added twice`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1")
        val second = ScenarioGraph.addRequestNode(scenario, "request-2", afterNodeId = null)

        ScenarioGraph.addDependency(scenario, first.id, second.id)
        ScenarioGraph.addDependency(scenario, first.id, second.id)

        assertEquals(1, scenario.edges.count { it.from == first.id && it.to == second.id })
    }

    @Test
    fun `labels fall back to the request name`() {
        val requestNode = ScenarioNode(kind = ScenarioNode.KIND_REQUEST)

        assertEquals("POST /login", ScenarioGraph.label(requestNode, "POST /login"))
        assertEquals("Start", ScenarioGraph.label(ScenarioNode(kind = ScenarioNode.KIND_START)))
        assertEquals("End", ScenarioGraph.label(ScenarioNode(kind = ScenarioNode.KIND_END)))
        assertNull(ScenarioGraph.node(scenario(), "missing"))
    }
}
