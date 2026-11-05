package dev.smartrequest.scenario

import dev.smartrequest.extract.ExtractedValue
import dev.smartrequest.extract.ResponseExtractorEngine
import dev.smartrequest.http.HttpResponseData
import dev.smartrequest.http.HttpTransport
import dev.smartrequest.http.PreparedRequest
import dev.smartrequest.http.RequestBuildException
import dev.smartrequest.http.RequestPreparer
import dev.smartrequest.http.VariableResolver
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.model.SmartRequestState
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

enum class NodeRunState { PENDING, RUNNING, PASSED, FAILED, SKIPPED, CANCELLED }

enum class ScenarioRunState { RUNNING, PASSED, FAILED, CANCELLED }

data class ScenarioNodeResult(
    val nodeId: String,
    val name: String,
    val state: NodeRunState,
    val method: String = "",
    val url: String = "",
    val statusCode: Int = 0,
    val statusText: String = "",
    val durationMs: Long = 0,
    val error: String? = null,
    val outputs: List<ExtractedValue> = emptyList(),
    val response: HttpResponseData? = null
)

data class CapturedVariable(
    val name: String,
    val value: String,
    val producerNodeId: String
)

data class ScenarioRunResult(
    val scenarioId: String,
    val state: ScenarioRunState,
    val startedAtMs: Long,
    val durationMs: Long,
    val nodes: Map<String, ScenarioNodeResult>,
    val variables: List<CapturedVariable>,
    val failure: String? = null
) {
    fun resultFor(nodeId: String): ScenarioNodeResult? = nodes[nodeId]

    val passedCount: Int get() = nodes.values.count { it.state == NodeRunState.PASSED }
    val failedCount: Int get() = nodes.values.count { it.state == NodeRunState.FAILED }
    val skippedCount: Int get() = nodes.values.count { it.state == NodeRunState.SKIPPED }
}

sealed interface ScenarioRunEvent {
    val runId: String

    data class RunStarted(override val runId: String, val nodeCount: Int) : ScenarioRunEvent
    data class NodeStarted(override val runId: String, val nodeId: String) : ScenarioRunEvent
    data class NodeFinished(override val runId: String, val result: ScenarioNodeResult) : ScenarioRunEvent
    data class RunFinished(override val runId: String, val result: ScenarioRunResult) : ScenarioRunEvent
}

/**
 * Immutable snapshot of the environment a run uses. Built once at start so switching the active
 * environment while a flow is running cannot mix hosts between nodes.
 */
data class RunEnvironment(
    val id: String = "",
    val name: String = "environment",
    val baseUrl: String = "http://localhost",
    val variables: List<KeyValue> = emptyList(),
    val globalHeaders: List<KeyValue> = emptyList(),
    val globalParams: List<KeyValue> = emptyList(),
    val timeoutMs: Int = SmartRequestState.DEFAULT_TIMEOUT_MS,
    val followRedirects: Boolean = true
) {
    fun environmentConfig(): EnvironmentConfig = EnvironmentConfig(
        id = id.ifBlank { "run" },
        name = name,
        baseUrl = baseUrl,
        variables = variables.map { it.deepCopy() }.toMutableList(),
        globalHeaders = globalHeaders.map { it.deepCopy() }.toMutableList(),
        globalParams = globalParams.map { it.deepCopy() }.toMutableList()
    )

    companion object {
        fun from(
            environment: EnvironmentConfig?,
            timeoutMs: Int = SmartRequestState.DEFAULT_TIMEOUT_MS,
            followRedirects: Boolean = true
        ): RunEnvironment = RunEnvironment(
            id = environment?.id.orEmpty(),
            name = environment?.name ?: "environment",
            baseUrl = environment?.baseUrl ?: "http://localhost",
            variables = environment?.variables?.map { it.deepCopy() } ?: emptyList(),
            globalHeaders = environment?.globalHeaders?.map { it.deepCopy() } ?: emptyList(),
            globalParams = environment?.globalParams?.map { it.deepCopy() } ?: emptyList(),
            timeoutMs = timeoutMs.coerceIn(SmartRequestState.MIN_TIMEOUT_MS, SmartRequestState.MAX_TIMEOUT_MS),
            followRedirects = followRedirects
        )
    }
}

/** The one side effect a run has: it sends requests. Tests replace this with a controllable fake. */
fun interface ScenarioTransport {
    fun send(request: PreparedRequest): CompletableFuture<HttpResponseData>
}

/** Resolves the request a node points at. The runner never touches project state directly. */
fun interface RequestSource {
    fun request(id: String): HttpRequestConfig?
}

class ScenarioRunHandle internal constructor(
    val runId: String,
    val result: CompletableFuture<ScenarioRunResult>,
    private val cancelAction: () -> Unit
) {
    fun cancel() {
        cancelAction()
    }
}

/**
 * Executes a scenario as a DAG with fail-fast semantics:
 *
 * - START passes immediately; a node becomes runnable when every predecessor passed;
 * - at most [ScenarioConfig.maxParallel] nodes run at a time;
 * - the first failure cancels in-flight siblings, skips everything that has not started yet and
 *   finishes the run as FAILED;
 * - captured values live in a per-run scope layered over the environment and the run parameters,
 *   and are never written back to the environment.
 *
 * All state transitions happen on a single scheduler thread, which keeps the result deterministic
 * and makes the class safe to use without additional locking.
 */
class ScenarioRunner(
    private val transport: ScenarioTransport,
    private val requests: RequestSource,
    private val listener: (ScenarioRunEvent) -> Unit = {}
) {

    fun start(
        scenario: ScenarioConfig,
        environment: RunEnvironment = RunEnvironment(),
        parameters: Map<String, String> = emptyMap()
    ): ScenarioRunHandle {
        // Snapshot everything a run depends on, so edits made during the run only affect the next one.
        val snapshot = scenario.deepCopy().apply { normalize() }
        val future = CompletableFuture<ScenarioRunResult>()
        val run = ActiveRun(UUID.randomUUID().toString(), snapshot, environment, parameters, future)
        runCatching { run.begin() }.onFailure { future.completeExceptionally(it) }
        return ScenarioRunHandle(run.runId, future) { run.cancel() }
    }

    private inner class ActiveRun(
        val runId: String,
        private val scenario: ScenarioConfig,
        private val environment: RunEnvironment,
        parameters: Map<String, String>,
        private val result: CompletableFuture<ScenarioRunResult>
    ) {
        private val nodes: Map<String, ScenarioNode> = scenario.nodes.associateBy { it.id }
        private val successors = HashMap<String, MutableList<String>>()
        private val remainingPredecessors = HashMap<String, MutableSet<String>>()
        private val results = LinkedHashMap<String, ScenarioNodeResult>()
        private val pending = LinkedHashSet<String>()
        private val ready = ArrayList<String>()
        private val running = HashMap<String, CompletableFuture<*>>()
        private val transportFutures = ConcurrentHashMap<String, CompletableFuture<HttpResponseData>>()
        private val captured = LinkedHashMap<String, CapturedVariable>()
        private val scope = LinkedHashMap<String, String>()
        private val startedAtMs = System.currentTimeMillis()
        private val startedAtNanos = System.nanoTime()
        private var finished = false
        private var failure: String? = null

        private val maxParallel = scenario.maxParallel.coerceIn(
            ScenarioConfig.MIN_MAX_PARALLEL,
            ScenarioConfig.MAX_MAX_PARALLEL
        )
        private val endNodeId = scenario.nodes.firstOrNull { it.kind == ScenarioNode.KIND_END }?.id
        private val scheduler: ExecutorService = Executors.newSingleThreadExecutor(daemonThreads("scenario-scheduler"))
        private val workers: ExecutorService = Executors.newFixedThreadPool(maxParallel, daemonThreads("scenario-node"))

        init {
            scenario.nodes.forEach { pending.add(it.id) }
            scenario.edges.forEach { edge ->
                if (edge.from !in nodes || edge.to !in nodes) return@forEach
                successors.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
                remainingPredecessors.getOrPut(edge.to) { LinkedHashSet() }.add(edge.from)
            }
            scenario.nodes.forEach { node -> remainingPredecessors.getOrPut(node.id) { LinkedHashSet() } }

            val environmentVariables = VariableResolver.environmentVariables(environment.environmentConfig())
            scope.putAll(environmentVariables)
            parameters.forEach { (name, value) ->
                val key = name.trim()
                if (key.isNotEmpty() && key != ResponseExtractorEngine.RESERVED_VARIABLE) {
                    scope[key] = value
                }
            }
        }

        fun begin() {
            schedule {
                emit(ScenarioRunEvent.RunStarted(runId, nodes.size))
                val start = nodes.values.firstOrNull { it.kind == ScenarioNode.KIND_START }
                if (start == null) {
                    complete(ScenarioRunState.FAILED, "The scenario has no Start node")
                    return@schedule
                }
                markTerminal(start, NodeRunState.PASSED)
                releaseSuccessors(start.id)
            }
        }

        fun cancel() {
            if (result.isDone) return
            schedule { abort(ScenarioRunState.CANCELLED, "Run cancelled") }
        }

        /**
         * Runs [task] on the scheduler thread. A bug in a node cannot leave the run hanging: the
         * exception finishes the run as FAILED instead of swallowing the future.
         */
        private fun schedule(task: () -> Unit) {
            try {
                scheduler.execute {
                    try {
                        task()
                    } catch (t: Throwable) {
                        if (!result.isDone) {
                            complete(
                                ScenarioRunState.FAILED,
                                "Run failed: ${t.message ?: t.javaClass.simpleName}"
                            )
                        }
                    }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // The run already finished and its scheduler was shut down.
            }
        }

        private fun releaseSuccessors(nodeId: String) {
            if (finished) return
            successors[nodeId].orEmpty().forEach { nextId ->
                val remaining = remainingPredecessors[nextId] ?: return@forEach
                if (remaining.remove(nodeId) && remaining.isEmpty() && nextId in pending && nextId !in ready) {
                    ready.add(nextId)
                }
            }
            drainReady()
        }

        private fun drainReady() {
            if (finished) return
            ready.sortWith(compareBy<String>({ nodes[it]?.y ?: 0 }, { nodes[it]?.x ?: 0 }, { it }))
            while (running.size < maxParallel && ready.isNotEmpty()) {
                val nodeId = ready.removeAt(0)
                val node = nodes[nodeId] ?: continue
                if (nodeId !in pending) continue
                when (node.kind) {
                    ScenarioNode.KIND_START -> Unit
                    ScenarioNode.KIND_END -> markTerminal(node, NodeRunState.PASSED)
                    else -> submitRequestNode(node)
                }
            }
            maybeFinish()
        }

        private fun submitRequestNode(node: ScenarioNode) {
            val request = node.requestId?.let { requests.request(it) }
            if (request == null) {
                failNow(node, "Request was deleted; choose another request for this node")
                return
            }
            val timeoutMs = (node.timeoutMs ?: environment.timeoutMs)
                .coerceIn(SmartRequestState.MIN_TIMEOUT_MS, SmartRequestState.MAX_TIMEOUT_MS)
            val values = LinkedHashMap(scope)
            val prepared = try {
                RequestPreparer.prepare(
                    request = request,
                    environment = environment.environmentConfig(),
                    globalHeaders = environment.globalHeaders,
                    globalParams = environment.globalParams,
                    timeoutMs = timeoutMs,
                    followRedirects = environment.followRedirects,
                    variables = values
                ).request
            } catch (e: RequestBuildException) {
                failNow(node, e.message ?: "Request could not be prepared")
                return
            } catch (e: Exception) {
                failNow(node, e.message ?: "Request could not be prepared")
                return
            }

            pending.remove(node.id)
            results[node.id] = ScenarioNodeResult(
                nodeId = node.id,
                name = label(node),
                state = NodeRunState.RUNNING,
                method = prepared.method,
                url = prepared.url
            )
            emit(ScenarioRunEvent.NodeStarted(runId, node.id))

            val startedNanos = System.nanoTime()
            val future = CompletableFuture.supplyAsync(
                { evaluateWithRetries(node, prepared, startedNanos) },
                workers
            )
            running[node.id] = future
            future.whenComplete { nodeResult, _ ->
                schedule {
                    running.remove(node.id)
                    transportFutures.remove(node.id)
                    if (finished) return@schedule
                    if (nodeResult == null) {
                        failNow(node, "The node did not produce a result")
                    } else {
                        onNodeFinished(node, nodeResult)
                    }
                    drainReady()
                }
            }
        }

        /**
         * Runs one node with its retry budget. Every attempt sends the same prepared request; a
         * failed transport call, status expectation or required capture triggers another attempt.
         * The future of the current attempt is published so cancelling the run aborts it.
         */
        private fun evaluateWithRetries(
            node: ScenarioNode,
            prepared: PreparedRequest,
            startedNanos: Long
        ): ScenarioNodeResult {
            val attempts = node.retries.coerceIn(0, ScenarioNode.MAX_RETRIES) + 1
            var last: ScenarioNodeResult? = null
            for (attempt in 1..attempts) {
                val sendFuture = try {
                    transport.send(prepared)
                } catch (e: Exception) {
                    last = failedResult(node, prepared, startedNanos, HttpTransport.describeError(e))
                    continue
                }
                transportFutures[node.id] = sendFuture
                val result = try {
                    val response = sendFuture.get()
                    evaluateResponse(node, prepared, response, startedNanos)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return cancelledResult(node, prepared, startedNanos, "Run cancelled")
                } catch (e: CancellationException) {
                    return cancelledResult(node, prepared, startedNanos, "Run cancelled")
                } catch (e: ExecutionException) {
                    failedResult(node, prepared, startedNanos, HttpTransport.describeError(e))
                } catch (e: Exception) {
                    failedResult(node, prepared, startedNanos, HttpTransport.describeError(e))
                }
                last = result
                if (result.state == NodeRunState.PASSED) break
            }
            val finalResult = last ?: failedResult(
                node,
                prepared,
                startedNanos,
                "The node did not produce a result"
            )
            return if (finalResult.state == NodeRunState.FAILED && attempts > 1) {
                finalResult.copy(error = "${finalResult.error} (after $attempts attempts)")
            } else {
                finalResult
            }
        }

        private fun evaluateResponse(
            node: ScenarioNode,
            prepared: PreparedRequest,
            response: HttpResponseData,
            startedNanos: Long
        ): ScenarioNodeResult {
            val expectationError = StatusExpectation.error(node.expectStatus)
            if (expectationError != null) {
                return failedResult(node, prepared, startedNanos, expectationError, response)
            }
            if (!StatusExpectation.matches(node.expectStatus, response.statusCode)) {
                val message = "Expected ${StatusExpectation.describe(node.expectStatus)}, " +
                        "got ${response.statusCode} ${response.statusText}".trim()
                return failedResult(node, prepared, startedNanos, message, response)
            }

            val captures = try {
                ResponseExtractorEngine.extract(response, node.outputs.toList())
            } catch (e: Exception) {
                return failedResult(node, prepared, startedNanos, e.message ?: "Capture failed", response)
            }
            // Optional captures are recorded in the result (so the UI can explain the failure) but
            // do not fail the node; only required failures do.
            val optionalIds = node.outputs.filter { !it.required }.map { it.id }.toSet()
            val failures = captures.filter { !it.successful && it.extractorId !in optionalIds }
            if (failures.isNotEmpty()) {
                val message = failures.joinToString("; ") { value ->
                    val name = value.variable.ifBlank { value.source }
                    "$name: ${value.error}"
                }
                return ScenarioNodeResult(
                    nodeId = node.id,
                    name = label(node),
                    state = NodeRunState.FAILED,
                    method = prepared.method,
                    url = prepared.url,
                    statusCode = response.statusCode,
                    statusText = response.statusText,
                    durationMs = elapsedMs(startedNanos),
                    error = "Capture failed: $message",
                    outputs = captures,
                    response = response
                )
            }
            return ScenarioNodeResult(
                nodeId = node.id,
                name = label(node),
                state = NodeRunState.PASSED,
                method = prepared.method,
                url = prepared.url,
                statusCode = response.statusCode,
                statusText = response.statusText,
                durationMs = elapsedMs(startedNanos),
                outputs = captures,
                response = response
            )
        }

        private fun onNodeFinished(node: ScenarioNode, nodeResult: ScenarioNodeResult) {
            results[node.id] = nodeResult
            emit(ScenarioRunEvent.NodeFinished(runId, nodeResult))
            if (nodeResult.state != NodeRunState.PASSED) {
                abort(ScenarioRunState.FAILED, nodeResult.error ?: "Node '${label(node)}' failed")
                return
            }
            nodeResult.outputs
                .filter { it.successful }
                .forEach { value ->
                    val name = value.variable.trim()
                    if (name.isEmpty() || name == ResponseExtractorEngine.RESERVED_VARIABLE) return@forEach
                    val capturedValue = value.value.orEmpty()
                    scope[name] = capturedValue
                    captured[name] = CapturedVariable(name, capturedValue, node.id)
                }
            releaseSuccessors(node.id)
        }

        private fun failNow(node: ScenarioNode, message: String) {
            pending.remove(node.id)
            val nodeResult = ScenarioNodeResult(
                nodeId = node.id,
                name = label(node),
                state = NodeRunState.FAILED,
                method = requestMethod(node),
                error = message
            )
            results[node.id] = nodeResult
            emit(ScenarioRunEvent.NodeFinished(runId, nodeResult))
            abort(ScenarioRunState.FAILED, message)
        }

        private fun abort(state: ScenarioRunState, reason: String?) {
            if (finished) return
            finished = true
            failure = reason

            val runningIds = running.keys.toList()
            runningIds.forEach { id ->
                transportFutures[id]?.cancel(true)
                running[id]?.cancel(true)
            }
            runningIds.forEach { id ->
                val node = nodes[id] ?: return@forEach
                val current = results[id]
                if (current != null && current.state != NodeRunState.RUNNING) return@forEach
                val cancelled = ScenarioNodeResult(
                    nodeId = id,
                    name = label(node),
                    state = NodeRunState.CANCELLED,
                    method = current?.method.orEmpty(),
                    url = current?.url.orEmpty(),
                    durationMs = elapsedMs(startedAtNanos),
                    error = "Run stopped before this node finished"
                )
                results[id] = cancelled
                emit(ScenarioRunEvent.NodeFinished(runId, cancelled))
            }

            pending.toList().forEach { id ->
                val node = nodes[id] ?: return@forEach
                val skipped = ScenarioNodeResult(
                    nodeId = id,
                    name = label(node),
                    state = NodeRunState.SKIPPED,
                    method = requestMethod(node),
                    error = reason
                )
                results[id] = skipped
                emit(ScenarioRunEvent.NodeFinished(runId, skipped))
            }
            pending.clear()
            ready.clear()
            complete(state, reason)
        }

        private fun maybeFinish() {
            if (finished) return
            if (running.isNotEmpty() || ready.isNotEmpty()) return
            if (pending.isEmpty()) {
                if (endReached()) {
                    complete(ScenarioRunState.PASSED, null)
                } else {
                    complete(ScenarioRunState.FAILED, failure ?: "End was not reached")
                }
            } else {
                // Nodes are left that can never become ready; validation should have caught this.
                abort(ScenarioRunState.FAILED, "The run could not reach the End node")
            }
        }

        private fun complete(state: ScenarioRunState, reason: String?) {
            if (result.isDone) return
            finished = true
            val finalState = if (state == ScenarioRunState.PASSED && !endReached()) {
                ScenarioRunState.FAILED
            } else {
                state
            }
            val finalFailure = when {
                finalState != ScenarioRunState.FAILED -> null
                reason != null -> reason
                else -> "End was not reached"
            }
            val nodeResults = LinkedHashMap<String, ScenarioNodeResult>()
            scenario.nodes.forEach { node ->
                nodeResults[node.id] = results[node.id] ?: ScenarioNodeResult(
                    nodeId = node.id,
                    name = label(node),
                    state = NodeRunState.SKIPPED,
                    method = requestMethod(node)
                )
            }
            val runResult = ScenarioRunResult(
                scenarioId = scenario.id,
                state = finalState,
                startedAtMs = startedAtMs,
                durationMs = elapsedMs(startedAtNanos),
                nodes = nodeResults,
                variables = captured.values.toList(),
                failure = finalFailure
            )
            emit(ScenarioRunEvent.RunFinished(runId, runResult))
            result.complete(runResult)
            scheduler.shutdown()
            workers.shutdownNow()
        }

        private fun endReached(): Boolean = endNodeId != null && results[endNodeId]?.state == NodeRunState.PASSED

        private fun markTerminal(node: ScenarioNode, state: NodeRunState) {
            pending.remove(node.id)
            val nodeResult = ScenarioNodeResult(
                nodeId = node.id,
                name = label(node),
                state = state
            )
            results[node.id] = nodeResult
            emit(ScenarioRunEvent.NodeFinished(runId, nodeResult))
        }

        private fun failedResult(
            node: ScenarioNode,
            prepared: PreparedRequest,
            startedNanos: Long,
            message: String,
            response: HttpResponseData? = null
        ): ScenarioNodeResult = ScenarioNodeResult(
            nodeId = node.id,
            name = label(node),
            state = NodeRunState.FAILED,
            method = prepared.method,
            url = prepared.url,
            statusCode = response?.statusCode ?: 0,
            statusText = response?.statusText.orEmpty(),
            durationMs = elapsedMs(startedNanos),
            error = message,
            response = response
        )

        private fun cancelledResult(
            node: ScenarioNode,
            prepared: PreparedRequest,
            startedNanos: Long,
            message: String
        ): ScenarioNodeResult = ScenarioNodeResult(
            nodeId = node.id,
            name = label(node),
            state = NodeRunState.CANCELLED,
            method = prepared.method,
            url = prepared.url,
            durationMs = elapsedMs(startedNanos),
            error = message
        )

        private fun label(node: ScenarioNode): String {
            if (node.name.isNotBlank()) return node.name
            return when (node.kind) {
                ScenarioNode.KIND_START -> "Start"
                ScenarioNode.KIND_END -> "End"
                else -> node.requestId?.let { requests.request(it)?.name } ?: "Request"
            }
        }

        private fun requestMethod(node: ScenarioNode): String {
            if (node.kind != ScenarioNode.KIND_REQUEST) return ""
            return node.requestId?.let { requests.request(it)?.method }.orEmpty()
        }

        private fun emit(event: ScenarioRunEvent) {
            runCatching { listener(event) }
        }

        private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
    }

    companion object {
        private fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "smart-request-$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
