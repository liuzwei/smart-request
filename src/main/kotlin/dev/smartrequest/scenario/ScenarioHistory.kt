package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode

/**
 * Snapshot-based undo/redo for graph edits. Every entry is a deep copy of the nodes and edges, so
 * undo restores positions, dependencies and captures exactly; scenario parameters and the name are
 * not part of graph editing and are left alone.
 *
 * The history is deliberately small and in-memory: it belongs to one editor tab, not to the project.
 */
class ScenarioHistory(private val limit: Int = DEFAULT_LIMIT) {

    private data class Snapshot(val nodes: List<ScenarioNode>, val edges: List<ScenarioEdge>)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSize: Int get() = undoStack.size
    val redoSize: Int get() = redoStack.size

    /** Call before mutating [scenario]; the current state becomes the step that undo returns to. */
    fun record(scenario: ScenarioConfig) {
        undoStack.addLast(capture(scenario))
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Drops the most recent recorded step, for a mutation that was rejected before changing anything. */
    fun discardLast() {
        undoStack.removeLastOrNull()
    }

    fun undo(scenario: ScenarioConfig): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(capture(scenario))
        restore(scenario, snapshot)
        return true
    }

    fun redo(scenario: ScenarioConfig): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(capture(scenario))
        while (undoStack.size > limit) undoStack.removeFirst()
        restore(scenario, snapshot)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun capture(scenario: ScenarioConfig): Snapshot = Snapshot(
        nodes = scenario.nodes.map { it.deepCopy() },
        edges = scenario.edges.map { it.deepCopy() }
    )

    private fun restore(scenario: ScenarioConfig, snapshot: Snapshot) {
        scenario.nodes.clear()
        scenario.nodes.addAll(snapshot.nodes.map { it.deepCopy() })
        scenario.edges.clear()
        scenario.edges.addAll(snapshot.edges.map { it.deepCopy() })
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
