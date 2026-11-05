package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import kotlin.math.roundToInt

/**
 * Auto layout for the graph view: a longest-path layering (left to right) with barycenter ordering
 * inside each layer. It is deterministic, needs no rendering and no platform APIs, so it can be
 * tested on its own.
 */
object ScenarioLayout {

    const val HORIZONTAL_GAP = 280
    const val VERTICAL_GAP = 130

    private const val BARYCENTER_PASSES = 3

    data class Position(val x: Int, val y: Int)

    fun compute(scenario: ScenarioConfig): Map<String, Position> {
        val starts = scenario.nodes.filter { it.kind == ScenarioNode.KIND_START }
        val ends = scenario.nodes.filter { it.kind == ScenarioNode.KIND_END }
        val requests = ScenarioGraph.requestNodes(scenario)
        val positions = LinkedHashMap<String, Position>()

        starts.forEach { positions[it.id] = Position(-HORIZONTAL_GAP, 0) }
        ends.forEach { positions[it.id] = Position(HORIZONTAL_GAP, 0) }
        if (requests.isEmpty()) return positions

        val order = ScenarioGraph.displayOrder(scenario)
        val layers = HashMap<String, Int>()
        order.forEach { node ->
            val predecessors = ScenarioGraph.requestPredecessors(scenario, node.id)
            layers[node.id] = if (predecessors.isEmpty()) {
                0
            } else {
                predecessors.maxOf { layers[it] ?: 0 } + 1
            }
        }
        val maxLayer = layers.values.maxOrNull() ?: 0
        val byLayer = (0..maxLayer).map { layer ->
            requests.filter { layers[it.id] == layer }.toMutableList()
        }

        // Keep the stored order as the initial ordering, then pull nodes towards their predecessors.
        val orderIndex = HashMap<String, Int>()
        byLayer.forEach { nodes -> nodes.forEachIndexed { index, node -> orderIndex[node.id] = index } }
        repeat(BARYCENTER_PASSES) {
            for (layer in 1..maxLayer) {
                val sorted = byLayer[layer].sortedWith(
                    compareBy(
                        { node ->
                            val predecessors = ScenarioGraph.requestPredecessors(scenario, node.id)
                            predecessors.mapNotNull { orderIndex[it] }.average().takeUnless { it.isNaN() }
                                ?: Double.MAX_VALUE
                        },
                        { node -> order.indexOf(node) }
                    )
                )
                byLayer[layer].clear()
                byLayer[layer].addAll(sorted)
                byLayer[layer].forEachIndexed { index, node -> orderIndex[node.id] = index }
            }
        }

        byLayer.forEachIndexed { layer, nodes ->
            val center = (nodes.size - 1) / 2.0
            nodes.forEachIndexed { index, node ->
                positions[node.id] = Position(
                    x = layer * HORIZONTAL_GAP,
                    y = ((index - center) * VERTICAL_GAP).roundToInt()
                )
            }
        }
        val endX = (maxLayer + 1) * HORIZONTAL_GAP
        starts.forEach { positions[it.id] = Position(-HORIZONTAL_GAP, 0) }
        ends.forEach { positions[it.id] = Position(endX, 0) }
        return positions
    }

    fun apply(scenario: ScenarioConfig) {
        val positions = compute(scenario)
        scenario.nodes.forEach { node ->
            val position = positions[node.id] ?: return@forEach
            node.x = position.x
            node.y = position.y
        }
    }
}
