package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode

/**
 * Pure editing operations on a [ScenarioConfig], shared by the steps table, the inspector and (later)
 * the graph canvas.
 *
 * The editor never asks the user to draw Start and End edges: they are derived from the request
 * dependencies. A request node with no request predecessors follows Start, and a request node with
 * no request successors leads to End. That keeps even a partly built flow valid and guarantees that
 * every dependency the user checks is reachable from Start and can reach End.
 *
 * All functions mutate [scenario] in place and never touch the project state or the UI, so the whole
 * editing model is testable without an IDE.
 */
object ScenarioGraph {

    data class EditResult(val ok: Boolean, val error: String? = null) {
        companion object {
            val OK = EditResult(true)

            fun rejected(error: String): EditResult = EditResult(false, error)
        }
    }

    fun requestNodes(scenario: ScenarioConfig): List<ScenarioNode> =
        scenario.nodes.filter { it.kind == ScenarioNode.KIND_REQUEST }

    fun node(scenario: ScenarioConfig, nodeId: String): ScenarioNode? =
        scenario.nodes.firstOrNull { it.id == nodeId }

    fun start(scenario: ScenarioConfig): ScenarioNode? =
        scenario.nodes.firstOrNull { it.kind == ScenarioNode.KIND_START }

    fun end(scenario: ScenarioConfig): ScenarioNode? =
        scenario.nodes.firstOrNull { it.kind == ScenarioNode.KIND_END }

    /** Request nodes that must pass before [nodeId] runs. Start and End are not included. */
    fun requestPredecessors(scenario: ScenarioConfig, nodeId: String): List<String> {
        val requests = requestNodes(scenario).map { it.id }.toSet()
        return scenario.edges
            .filter { it.to == nodeId && it.from in requests }
            .map { it.from }
            .distinct()
    }

    /** Request nodes that wait for [nodeId]. */
    fun requestSuccessors(scenario: ScenarioConfig, nodeId: String): List<String> {
        val requests = requestNodes(scenario).map { it.id }.toSet()
        return scenario.edges
            .filter { it.from == nodeId && it.to in requests }
            .map { it.to }
            .distinct()
    }

    /**
     * Replaces the request predecessors of [nodeId]. Rejects the change atomically when one of the
     * new dependencies would close a cycle, so a rejected edit never leaves a half-applied graph.
     */
    fun setDependencies(
        scenario: ScenarioConfig,
        nodeId: String,
        predecessorIds: Collection<String>
    ): EditResult {
        val node = node(scenario, nodeId) ?: return EditResult.rejected("The step no longer exists")
        if (!node.isRequestNode()) return EditResult.rejected("Only request steps have dependencies")
        val requestIds = requestNodes(scenario).map { it.id }.toSet()
        val targets = predecessorIds.filter { it != nodeId && it in requestIds }.distinct()

        val reachable = descendants(scenario, nodeId)
        val cycle = targets.firstOrNull { it in reachable }
        if (cycle != null) {
            val name = node(scenario, cycle)?.let(::label) ?: "that step"
            return EditResult.rejected("'$name' already runs after this step; the dependency would create a cycle")
        }

        scenario.edges.removeAll { it.to == nodeId && it.from in requestIds }
        targets.forEach { predecessor ->
            scenario.edges.add(ScenarioEdge(from = predecessor, to = nodeId))
        }
        syncTerminals(scenario)
        return EditResult.OK
    }

    fun addDependency(scenario: ScenarioConfig, predecessorId: String, nodeId: String): EditResult =
        setDependencies(scenario, nodeId, requestPredecessors(scenario, nodeId) + predecessorId)

    fun removeDependency(scenario: ScenarioConfig, predecessorId: String, nodeId: String): EditResult =
        setDependencies(scenario, nodeId, requestPredecessors(scenario, nodeId) - predecessorId)

    /**
     * Appends a request node and, when [afterNodeId] names another request node, makes it depend on
     * that one. Passing null creates a node that starts together with the other roots.
     */
    fun addRequestNode(
        scenario: ScenarioConfig,
        requestId: String,
        afterNodeId: String? = requestNodes(scenario).lastOrNull()?.id
    ): ScenarioNode {
        val node = ScenarioNode(
            kind = ScenarioNode.KIND_REQUEST,
            requestId = requestId,
            x = nextGridX(scenario),
            y = 0
        )
        scenario.nodes.add(node)
        end(scenario)?.let { end ->
            if (end.x <= node.x) {
                end.x = node.x + ScenarioConfig.TERMINAL_SPACING
                end.y = node.y
            }
        }
        val predecessor = afterNodeId?.let { node(scenario, it) }
        if (predecessor != null && predecessor.isRequestNode()) {
            scenario.edges.add(ScenarioEdge(from = predecessor.id, to = node.id))
        }
        syncTerminals(scenario)
        return node
    }

    fun removeNode(scenario: ScenarioConfig, nodeId: String): Boolean {
        val node = node(scenario, nodeId) ?: return false
        if (!node.isRequestNode()) return false
        // Keep the chain intact: whoever waited for this step now waits for its successors, and its
        // successors now wait for its predecessors. Removing a node cannot introduce a cycle.
        val predecessors = requestPredecessors(scenario, nodeId)
        val successors = requestSuccessors(scenario, nodeId)
        scenario.edges.removeAll { it.from == nodeId || it.to == nodeId }
        scenario.nodes.removeAll { it.id == nodeId }
        val remaining = requestNodes(scenario).map { it.id }.toSet()
        predecessors.filter { it in remaining }.forEach { predecessor ->
            successors.filter { it in remaining }.forEach { successor ->
                if (predecessor != successor &&
                    scenario.edges.none { it.from == predecessor && it.to == successor }
                ) {
                    scenario.edges.add(ScenarioEdge(from = predecessor, to = successor))
                }
            }
        }
        syncTerminals(scenario)
        return true
    }

    /**
     * Rebuilds the Start/End edges from the request dependencies: root steps follow Start and leaf
     * steps lead to End. Every other Start/End edge is removed, so the result is always a valid
     * single-entry, single-exit layout as long as request edges are acyclic.
     */
    fun syncTerminals(scenario: ScenarioConfig) {
        val start = start(scenario) ?: return
        val end = end(scenario) ?: return
        scenario.edges.removeAll { edge ->
            edge.from == start.id || edge.to == start.id || edge.from == end.id || edge.to == end.id
        }
        val requests = requestNodes(scenario)
        if (requests.isEmpty()) return
        val predecessors = requests.associate { it.id to requestPredecessors(scenario, it.id) }
        val successors = requests.associate { it.id to requestSuccessors(scenario, it.id) }
        requests.forEach { request ->
            if (predecessors[request.id].isNullOrEmpty()) {
                scenario.edges.add(ScenarioEdge(from = start.id, to = request.id))
            }
        }
        requests.forEach { request ->
            if (successors[request.id].isNullOrEmpty()) {
                scenario.edges.add(ScenarioEdge(from = request.id, to = end.id))
            }
        }
    }

    /**
     * Request nodes in execution order: a topological sort that keeps the stored order for nodes
     * that do not constrain each other. Cycles (which validation reports) fall back to stored order.
     */
    fun displayOrder(scenario: ScenarioConfig): List<ScenarioNode> {
        val requests = requestNodes(scenario)
        if (requests.isEmpty()) return emptyList()
        val index = requests.mapIndexed { position, node -> node.id to position }.toMap()
        val outgoing = HashMap<String, MutableList<String>>()
        val indegree = HashMap<String, Int>()
        requests.forEach { indegree[it.id] = 0 }
        requests.forEach { request ->
            requestSuccessors(scenario, request.id).forEach { next ->
                if (next == request.id) return@forEach
                outgoing.getOrPut(request.id) { mutableListOf() }.add(next)
                indegree[next] = (indegree[next] ?: 0) + 1
            }
        }
        val ready = java.util.PriorityQueue<String>(compareBy { index[it] ?: Int.MAX_VALUE })
        requests.forEach { request -> if ((indegree[request.id] ?: 0) == 0) ready.add(request.id) }
        val ordered = ArrayList<ScenarioNode>(requests.size)
        while (ready.isNotEmpty()) {
            val id = ready.poll()
            node(scenario, id)?.let(ordered::add)
            outgoing[id].orEmpty().forEach { next ->
                val remaining = (indegree[next] ?: 0) - 1
                indegree[next] = remaining
                if (remaining == 0) ready.add(next)
            }
        }
        return if (ordered.size == requests.size) ordered else requests
    }

    /** Request nodes that run, directly or transitively, before [nodeId]. */
    fun ancestors(scenario: ScenarioConfig, nodeId: String): Set<String> {
        val previous = requestNodes(scenario).associate { it.id to requestPredecessors(scenario, it.id) }
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(previous[nodeId].orEmpty())
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (seen.add(id)) queue.addAll(previous[id].orEmpty())
        }
        return seen
    }

    /** Request nodes that run, directly or transitively, after [nodeId]. */
    fun descendants(scenario: ScenarioConfig, nodeId: String): Set<String> {
        val previous = requestNodes(scenario).associate { it.id to requestPredecessors(scenario, it.id) }
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        previous.forEach { (id, predecessors) ->
            if (nodeId in predecessors) queue.add(id)
        }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (seen.add(id)) {
                previous.forEach { (candidate, predecessors) ->
                    if (id in predecessors) queue.add(candidate)
                }
            }
        }
        return seen
    }

    fun requiresDependency(scenario: ScenarioConfig, nodeId: String, predecessorId: String): Boolean =
        predecessorId in requestPredecessors(scenario, nodeId)

    /**
     * Why the edge `from → to` cannot be added, or null when it can. Used by the canvas for live
     * feedback while the user drags a connection; [addDependency] re-checks before mutating.
     */
    fun dependencyError(scenario: ScenarioConfig, fromId: String, toId: String): String? {
        val from = node(scenario, fromId) ?: return "The source step no longer exists"
        val to = node(scenario, toId) ?: return "The target step no longer exists"
        if (from.id == to.id) return "A step cannot depend on itself"
        if (from.kind == ScenarioNode.KIND_END) return "End has no outgoing dependencies"
        if (to.kind == ScenarioNode.KIND_START) return "Start cannot have incoming dependencies"
        if (!from.isRequestNode()) return "Start cannot be used as a dependency"
        if (!to.isRequestNode()) return "End cannot depend on a step"
        if (requiresDependency(scenario, toId, fromId)) return "Already a dependency"
        if (fromId in descendants(scenario, toId)) return "Would create a cycle"
        return null
    }

    fun nextGridX(scenario: ScenarioConfig): Int {
        val rightmost = requestNodes(scenario).maxOfOrNull { it.x } ?: -ScenarioConfig.TERMINAL_SPACING
        return rightmost + ScenarioConfig.TERMINAL_SPACING
    }

    fun label(node: ScenarioNode, requestName: String? = null): String {
        if (node.name.isNotBlank()) return node.name
        if (node.isRequestNode() && !requestName.isNullOrBlank()) return requestName
        return when (node.kind) {
            ScenarioNode.KIND_START -> "Start"
            ScenarioNode.KIND_END -> "End"
            else -> "Request"
        }
    }
}
