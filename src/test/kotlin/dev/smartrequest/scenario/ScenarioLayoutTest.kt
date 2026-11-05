package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioLayoutTest {

    private fun scenario(): ScenarioConfig = ScenarioConfig(name = "Layout").apply { normalize() }

    @Test
    fun `a linear flow is laid out left to right on one row`() {
        val scenario = scenario()
        val first = ScenarioGraph.addRequestNode(scenario, "request-1", afterNodeId = null)
        val second = ScenarioGraph.addRequestNode(scenario, "request-2")
        val third = ScenarioGraph.addRequestNode(scenario, "request-3")

        val positions = ScenarioLayout.compute(scenario)

        assertEquals(0, positions.getValue(first.id).x)
        assertEquals(0, positions.getValue(first.id).y)
        assertEquals(ScenarioLayout.HORIZONTAL_GAP, positions.getValue(second.id).x)
        assertEquals(ScenarioLayout.HORIZONTAL_GAP * 2, positions.getValue(third.id).x)
        assertEquals(-ScenarioLayout.HORIZONTAL_GAP, positions.getValue(ScenarioGraph.start(scenario)!!.id).x)
        assertEquals(
            ScenarioLayout.HORIZONTAL_GAP * 3,
            positions.getValue(ScenarioGraph.end(scenario)!!.id).x
        )
        assertEquals(0, positions.getValue(second.id).y)
    }

    @Test
    fun `branches share a layer and a join is centered between them`() {
        val scenario = scenario()
        val root = ScenarioGraph.addRequestNode(scenario, "root", afterNodeId = null)
        val left = ScenarioGraph.addRequestNode(scenario, "left", afterNodeId = null)
        val right = ScenarioGraph.addRequestNode(scenario, "right", afterNodeId = null)
        val join = ScenarioGraph.addRequestNode(scenario, "join", afterNodeId = null)
        ScenarioGraph.setDependencies(scenario, left.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, right.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, join.id, listOf(left.id, right.id))

        val positions = ScenarioLayout.compute(scenario)

        assertEquals(positions.getValue(left.id).x, positions.getValue(right.id).x)
        assertNotEquals(positions.getValue(left.id).y, positions.getValue(right.id).y)
        assertEquals(positions.getValue(left.id).y + positions.getValue(right.id).y, positions.getValue(join.id).y)
        assertTrue(positions.getValue(join.id).x > positions.getValue(left.id).x)
        assertTrue(positions.getValue(join.id).y > positions.getValue(left.id).y)
        assertEquals(0, positions.getValue(root.id).y)
    }

    @Test
    fun `computing twice produces the same result and apply writes the model`() {
        val scenario = scenario()
        ScenarioGraph.addRequestNode(scenario, "request-1", afterNodeId = null)
        ScenarioGraph.addRequestNode(scenario, "request-2", afterNodeId = null)

        val first = ScenarioLayout.compute(scenario)
        val second = ScenarioLayout.compute(scenario)
        assertEquals(first, second)

        ScenarioLayout.apply(scenario)
        scenario.nodes.forEach { node ->
            val position = first.getValue(node.id)
            assertEquals(position.x, node.x)
            assertEquals(position.y, node.y)
        }
    }

    @Test
    fun `an empty scenario puts start and end on the same row`() {
        val scenario = scenario()

        val positions = ScenarioLayout.compute(scenario)

        assertEquals(2, positions.size)
        assertEquals(0, positions.getValue(ScenarioGraph.start(scenario)!!.id).y)
        assertEquals(0, positions.getValue(ScenarioGraph.end(scenario)!!.id).y)
        assertTrue(
            positions.getValue(ScenarioGraph.end(scenario)!!.id).x >
                positions.getValue(ScenarioGraph.start(scenario)!!.id).x
        )
    }

    @Test
    fun `a diamond layout does not place two nodes on the same spot`() {
        val scenario = scenario()
        val root = ScenarioGraph.addRequestNode(scenario, "root", afterNodeId = null)
        val left = ScenarioGraph.addRequestNode(scenario, "left", afterNodeId = null)
        val right = ScenarioGraph.addRequestNode(scenario, "right", afterNodeId = null)
        val join = ScenarioGraph.addRequestNode(scenario, "join", afterNodeId = null)
        ScenarioGraph.setDependencies(scenario, left.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, right.id, listOf(root.id))
        ScenarioGraph.setDependencies(scenario, join.id, listOf(left.id, right.id))

        val positions = ScenarioLayout.compute(scenario).values.toList()
        assertEquals(positions.size, positions.distinct().size)
        assertTrue(positions.all { it.x >= -ScenarioLayout.HORIZONTAL_GAP })
    }
}
