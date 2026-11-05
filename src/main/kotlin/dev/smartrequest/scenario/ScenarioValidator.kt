package dev.smartrequest.scenario

import dev.smartrequest.extract.JsonPath
import dev.smartrequest.extract.PathSyntaxException
import dev.smartrequest.extract.ResponseExtractorEngine
import dev.smartrequest.http.VariableResolver
import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode

/**
 * Static checks for a scenario, run before anything is sent. The data-flow checks are the important
 * part: they prove that every `{{variable}}` a node needs is produced by one of its upstream nodes,
 * or exists in the environment, or is a run parameter. Without them a flow only fails halfway
 * through, after it already changed state on the server.
 */
object ScenarioValidator {

    enum class Severity { ERROR, WARNING }

    data class Problem(
        val severity: Severity,
        val message: String,
        val nodeId: String? = null,
        val edgeId: String? = null
    )

    data class Report(
        val problems: List<Problem> = emptyList(),
        val requestNodeCount: Int = 0,
        /** Variables that no upstream node captures, no environment defines, and no parameter supplies. */
        val missingVariables: Set<String> = emptySet()
    ) {
        val errors: List<Problem> get() = problems.filter { it.severity == Severity.ERROR }
        val warnings: List<Problem> get() = problems.filter { it.severity == Severity.WARNING }
        val isValid: Boolean get() = errors.isEmpty()

        /** A scenario without request nodes has nothing to run, even when it is structurally valid. */
        val canRun: Boolean get() = isValid && requestNodeCount > 0
    }

    fun validate(
        scenario: ScenarioConfig,
        requestSource: (String) -> HttpRequestConfig?,
        environmentVariables: Map<String, String> = emptyMap(),
        parameters: Map<String, String> = emptyMap()
    ): Report {
        val problems = mutableListOf<Problem>()

        fun error(message: String, nodeId: String? = null, edgeId: String? = null) {
            problems.add(Problem(Severity.ERROR, message, nodeId, edgeId))
        }

        fun warn(message: String, nodeId: String? = null, edgeId: String? = null) {
            problems.add(Problem(Severity.WARNING, message, nodeId, edgeId))
        }

        val nodesById = LinkedHashMap<String, ScenarioNode>()
        scenario.nodes.forEach { node ->
            if (node.id.isBlank()) {
                error("A node has no id")
                return@forEach
            }
            if (nodesById.containsKey(node.id)) {
                error("Two nodes share the id '${node.id}'", node.id)
                return@forEach
            }
            nodesById[node.id] = node
        }

        val starts = scenario.nodes.filter { it.kind == ScenarioNode.KIND_START }
        val ends = scenario.nodes.filter { it.kind == ScenarioNode.KIND_END }
        if (starts.isEmpty()) error("Scenario has no Start node")
        if (starts.size > 1) error("Scenario has ${starts.size} Start nodes; keep one")
        if (ends.isEmpty()) error("Scenario has no End node")
        if (ends.size > 1) error("Scenario has ${ends.size} End nodes; keep one")

        val requestNodes = scenario.nodes.filter { it.kind == ScenarioNode.KIND_REQUEST }
        if (requestNodes.isEmpty()) warn("Add at least one request node")

        // Duplicate ids make every graph lookup ambiguous; stop after reporting them.
        if (nodesById.size != scenario.nodes.size) {
            return Report(problems.toList(), requestNodes.size)
        }

        val requestByNode = LinkedHashMap<String, HttpRequestConfig>()
        val requestCache = HashMap<String, HttpRequestConfig?>()

        fun label(node: ScenarioNode): String {
            if (node.name.isNotBlank()) return node.name
            return when (node.kind) {
                ScenarioNode.KIND_START -> "Start"
                ScenarioNode.KIND_END -> "End"
                else -> requestByNode[node.id]?.name ?: "node ${node.id.take(8)}"
            }
        }

        fun requestFor(node: ScenarioNode): HttpRequestConfig? {
            val requestId = node.requestId ?: return null
            if (!requestCache.containsKey(requestId)) requestCache[requestId] = requestSource(requestId)
            return requestCache[requestId]
        }

        requestNodes.forEach { node ->
            val requestId = node.requestId
            if (requestId.isNullOrBlank()) {
                error("Node '${label(node)}' has no request", node.id)
                return@forEach
            }
            val request = requestFor(node)
            if (request == null) {
                error("Request used by node '${label(node)}' no longer exists; choose another request", node.id)
                return@forEach
            }
            requestByNode[node.id] = request
            StatusExpectation.error(node.expectStatus)?.let { message ->
                error("Node '${label(node)}': $message", node.id)
            }
            validateOutputs(node, ::label, ::error, ::warn)
        }

        val parameterKeys = parameters.keys.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        parameterKeys.forEach { name ->
            if (name == ResponseExtractorEngine.RESERVED_VARIABLE) {
                error("Run parameter '$name' is reserved for the environment host")
            } else if (name.any { it.isWhitespace() || it == '{' || it == '}' }) {
                warn("Run parameter '$name' cannot be used in {{...}}")
            }
        }

        val edgeKeys = HashSet<String>()
        val outgoing = HashMap<String, MutableList<String>>()
        val incoming = HashMap<String, MutableList<String>>()
        scenario.edges.forEach { edge ->
            val from = nodesById[edge.from]
            val to = nodesById[edge.to]
            if (from == null || to == null) {
                error("A dependency points to a node that no longer exists", edgeId = edge.id)
                return@forEach
            }
            if (edge.from == edge.to) {
                error("Node '${label(from)}' cannot depend on itself", edgeId = edge.id)
                return@forEach
            }
            if (from.kind == ScenarioNode.KIND_END) {
                error("End cannot have outgoing dependencies", edgeId = edge.id)
            }
            if (to.kind == ScenarioNode.KIND_START) {
                error("Start cannot have incoming dependencies", edgeId = edge.id)
            }
            if (!edgeKeys.add("${edge.from}\u0000${edge.to}")) {
                error("Duplicate dependency '${label(from)}' → '${label(to)}'", edgeId = edge.id)
            }
            outgoing.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
            incoming.getOrPut(edge.to) { mutableListOf() }.add(edge.from)
        }

        if (requestNodes.isNotEmpty() && starts.size == 1) {
            if (outgoing[starts.first().id].isNullOrEmpty()) {
                error("Start has no outgoing dependency")
            }
        }
        if (requestNodes.isNotEmpty() && ends.size == 1) {
            if (incoming[ends.first().id].isNullOrEmpty()) {
                error("End has no incoming dependency")
            }
            if (starts.size == 1) {
                val reachable = reachableFrom(starts.first().id, outgoing)
                scenario.nodes.forEach { node ->
                    if (node.kind == ScenarioNode.KIND_START || node.id in reachable) return@forEach
                    if (node.kind == ScenarioNode.KIND_END) {
                        error("End is not reachable from Start", node.id)
                    } else {
                        error("Node '${label(node)}' is not reachable from Start", node.id)
                    }
                }
                val canReachEnd = reachableFrom(ends.first().id, incoming)
                scenario.nodes.forEach { node ->
                    if (node.kind == ScenarioNode.KIND_END || node.id in canReachEnd) return@forEach
                    error("Node '${label(node)}' cannot reach End", node.id)
                }
            }
        }

        val order = topologicalOrder(scenario.nodes, outgoing, incoming)
        val missingVariables = LinkedHashSet<String>()
        if (order == null) {
            val cycle = findCycle(scenario.nodes, outgoing)
            val path = cycle?.joinToString(" → ") { id -> nodesById[id]?.let { label(it) } ?: id }
            error("Scenario has a cycle${if (path.isNullOrEmpty()) "" else ": $path"}")
        } else if (starts.size == 1 && ends.size == 1) {
            validateDataFlow(
                requestNodes = requestNodes,
                nodesById = nodesById,
                order = order,
                incoming = incoming,
                requestFor = ::requestFor,
                label = ::label,
                environmentVariables = environmentVariables,
                parameterKeys = parameterKeys,
                missingVariables = missingVariables,
                error = ::error,
                warn = ::warn
            )
        }

        return Report(problems.toList(), requestNodes.size, missingVariables)
    }

    private fun validateOutputs(
        node: ScenarioNode,
        label: (ScenarioNode) -> String,
        error: (String, String?, String?) -> Unit,
        warn: (String, String?, String?) -> Unit
    ) {
        val names = ArrayList<String>()
        node.outputs.filter { it.enabled && !it.isBlank() }.forEach { rule ->
            val variable = ResponseExtractorEngine.resolveVariableName(rule.variable, rule.source)
            when {
                variable.isEmpty() ->
                    error("Node '${label(node)}' has a capture rule without a variable name", node.id, null)

                variable.any { it.isWhitespace() || it == '{' || it == '}' } ->
                    error(
                        "Node '${label(node)}': capture variable '$variable' must not contain spaces or braces",
                        node.id,
                        null
                    )

                variable == ResponseExtractorEngine.RESERVED_VARIABLE ->
                    error(
                        "Node '${label(node)}': ${ResponseExtractorEngine.RESERVED_VARIABLE} is reserved for the environment host",
                        node.id,
                        null
                    )

                else -> names.add(variable)
            }
            val source = rule.source.trim()
            if (source.isEmpty()) {
                error("Node '${label(node)}': capture rule '${variable.ifEmpty { "?" }}' has no expression", node.id, null)
            } else {
                try {
                    JsonPath.parse(source)
                } catch (e: PathSyntaxException) {
                    error(
                        "Node '${label(node)}': invalid capture expression '$source': ${e.message}",
                        node.id,
                        null
                    )
                }
            }
        }
        names.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (name, count) ->
            val message = "Node '${label(node)}' captures {{$name}} $count times; the last rule wins"
            warn(message, node.id, null)
        }
    }

    private fun validateDataFlow(
        requestNodes: List<ScenarioNode>,
        nodesById: Map<String, ScenarioNode>,
        order: List<String>,
        incoming: Map<String, List<String>>,
        requestFor: (ScenarioNode) -> HttpRequestConfig?,
        label: (ScenarioNode) -> String,
        environmentVariables: Map<String, String>,
        parameterKeys: Set<String>,
        missingVariables: MutableSet<String>,
        error: (String, String?, String?) -> Unit,
        warn: (String, String?, String?) -> Unit
    ) {
        val outputsByNode = HashMap<String, List<String>>()
        requestNodes.forEach { node ->
            outputsByNode[node.id] = captureNames(node)
        }

        val producedBy = HashMap<String, MutableList<String>>()
        outputsByNode.forEach { (nodeId, names) ->
            names.distinct().forEach { name ->
                producedBy.getOrPut(name) { mutableListOf() }.add(nodeId)
            }
        }

        val available = HashMap<String, MutableSet<String>>()
        val ancestors = HashMap<String, Set<String>>()
        order.forEach { nodeId ->
            val predecessors = incoming[nodeId].orEmpty().distinct()
            val availableHere = LinkedHashSet<String>()
            val ancestorSet = LinkedHashSet<String>()
            predecessors.forEach { predecessor ->
                availableHere.addAll(available[predecessor].orEmpty())
                availableHere.addAll(outputsByNode[predecessor].orEmpty())
                ancestorSet.add(predecessor)
                ancestorSet.addAll(ancestors[predecessor].orEmpty())
            }
            available[nodeId] = availableHere
            ancestors[nodeId] = ancestorSet
        }

        requestNodes.forEach { node ->
            val request = requestFor(node) ?: return@forEach
            val needed = neededVariables(request)
            val missing = needed -
                    available[node.id].orEmpty() -
                    environmentVariables.keys -
                    parameterKeys
            missing.sorted().forEach { name ->
                val producers = producedBy[name].orEmpty().filter { it != node.id }.distinct()
                if (producers.isEmpty()) {
                    missingVariables.add(name)
                    error(
                        "Node '${label(node)}' needs {{$name}}; it is not an environment variable, " +
                                "not a run parameter, and no upstream node captures it",
                        node.id,
                        null
                    )
                } else {
                    val names = producers.joinToString(", ") { id ->
                        "'${nodesById[id]?.let { label(it) } ?: id}'"
                    }
                    error(
                        "Node '${label(node)}' needs {{$name}}, which is captured by $names, " +
                                "but that node is not upstream",
                        node.id,
                        null
                    )
                }
            }
        }

        producedBy.forEach { (name, producers) ->
            if (name in parameterKeys) {
                val names = producers.distinct().joinToString(", ") { id ->
                    "'${nodesById[id]?.let { label(it) } ?: id}'"
                }
                warn(
                    "{{$name}} is a run parameter but is also captured by $names; " +
                            "downstream nodes see the captured value",
                    null,
                    null
                )
            }
        }

        producedBy.forEach { (name, producers) ->
            val distinct = producers.distinct()
            if (distinct.size < 2) return@forEach
            val unordered = distinct.filter { a ->
                distinct.any { b -> a != b && a !in ancestors[b].orEmpty() && b !in ancestors[a].orEmpty() }
            }
            if (unordered.size >= 2) {
                val names = unordered.joinToString(", ") { id ->
                    "'${nodesById[id]?.let { label(it) } ?: id}'"
                }
                warn(
                    "Nodes $names all capture {{$name}}; when they run in parallel " +
                            "the value depends on completion order",
                    null,
                    null
                )
            }
        }
    }

    /** Every `{{name}}` that appears in the request, regardless of whether it currently resolves. */
    fun neededVariables(request: HttpRequestConfig): Set<String> {
        val texts = ArrayList<String>()
        texts.add(request.url)
        if (request.bodyTypeEnum() != BodyType.NONE) texts.add(request.body)
        // Disabled rows and a disabled body are never sent, so they must not trigger "missing variable".
        request.pathParams.forEach { parameter ->
            if (parameter.key.isNotBlank()) {
                texts.add(parameter.key)
                texts.add(parameter.value)
            }
        }
        request.params.filter { it.enabled }.forEach { parameter ->
            texts.add(parameter.key)
            texts.add(parameter.value)
        }
        request.headers.filter { it.enabled }.forEach { header ->
            texts.add(header.key)
            texts.add(header.value)
        }
        return texts.flatMapTo(LinkedHashSet()) { VariableResolver.unresolvedNames(it) }
    }

    fun captureNames(node: ScenarioNode): List<String> =
        node.outputs
            .filter { it.enabled && !it.isBlank() }
            .map { ResponseExtractorEngine.resolveVariableName(it.variable, it.source).trim() }
            .filter { it.isNotEmpty() }

    private fun reachableFrom(start: String, edges: Map<String, List<String>>): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        seen.add(start)
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            edges[current].orEmpty().forEach { next ->
                if (seen.add(next)) queue.add(next)
            }
        }
        return seen
    }

    private fun topologicalOrder(
        nodes: List<ScenarioNode>,
        outgoing: Map<String, List<String>>,
        incoming: Map<String, List<String>>
    ): List<String>? {
        val indegree = nodes.associate { it.id to incoming[it.id].orEmpty().distinct().size }.toMutableMap()
        val queue = ArrayDeque(nodes.filter { indegree[it.id] == 0 }.map { it.id })
        val order = ArrayList<String>(nodes.size)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order.add(id)
            outgoing[id].orEmpty().distinct().forEach { next ->
                val remaining = (indegree[next] ?: 0) - 1
                indegree[next] = remaining
                if (remaining == 0) queue.addLast(next)
            }
        }
        return if (order.size == nodes.size) order else null
    }

    private fun findCycle(nodes: List<ScenarioNode>, outgoing: Map<String, List<String>>): List<String>? {
        val state = HashMap<String, Int>()
        val stack = ArrayList<String>()

        fun visit(id: String): List<String>? {
            state[id] = 1
            stack.add(id)
            outgoing[id].orEmpty().distinct().forEach { next ->
                when (state[next] ?: 0) {
                    0 -> visit(next)?.let { return it }
                    1 -> {
                        val index = stack.indexOf(next)
                        return stack.subList(index, stack.size) + next
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            state[id] = 2
            return null
        }

        nodes.forEach { node ->
            if ((state[node.id] ?: 0) == 0) {
                visit(node.id)?.let { return it }
            }
        }
        return null
    }
}
