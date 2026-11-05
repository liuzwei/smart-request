package dev.smartrequest.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.smartrequest.model.EnvironmentConfig
import dev.smartrequest.model.SmartRequestState
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.model.RequestHistoryEntry
import dev.smartrequest.model.ScenarioConfig
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The component name and the legacy storage keep the pre-rename identifiers on purpose: projects
 * saved before the rename keep their environments and requests in `.idea/fast-request.xml`, which is
 * read once and rewritten as `.idea/smart-request.xml` on the next save.
 */
@State(
    name = "FastRequestState",
    storages = [
        Storage("smart-request.xml"),
        Storage(value = "fast-request.xml", deprecated = true)
    ]
)
@Service(Service.Level.PROJECT)
class SmartRequestProjectService : PersistentStateComponent<SmartRequestState> {

    private var myState: SmartRequestState = SmartRequestState.createDefault()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): SmartRequestState = myState

    override fun loadState(state: SmartRequestState) {
        myState = state
        myState.normalize()
    }

    fun state(): SmartRequestState = myState

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    fun findGroup(id: String): RequestGroup? = myState.groups.firstOrNull { it.id == id }

    fun findRequest(id: String): HttpRequestConfig? =
        myState.groups.asSequence().flatMap { it.requests.asSequence() }.firstOrNull { it.id == id }

    fun groupOf(requestId: String): RequestGroup? =
        myState.groups.firstOrNull { group -> group.requests.any { it.id == requestId } }

    fun activeEnvironment(): EnvironmentConfig? = myState.activeEnvironment()

    fun selectEnvironment(id: String) {
        if (myState.environments.any { it.id == id }) {
            myState.activeEnvironmentId = id
            notifyChanged()
        }
    }

    fun addGroup(name: String): RequestGroup {
        val group = RequestGroup()
        group.name = name
        myState.groups.add(group)
        notifyChanged()
        return group
    }

    fun addRequest(groupId: String, name: String = "New Request"): HttpRequestConfig {
        val group = findGroup(groupId)
            ?: myState.groups.firstOrNull()
            ?: RequestGroup(name = "My Requests").also { myState.groups.add(it) }
        return HttpRequestConfig().apply {
            this.name = name
            url = "{{baseUrl}}/"
            group.requests.add(this)
        }.also {
            group.expanded = true
            notifyChanged()
        }
    }

    fun removeRequest(requestId: String) {
        myState.groups.forEach { group -> group.requests.removeIf { it.id == requestId } }
        notifyChanged()
    }

    fun removeGroup(groupId: String) {
        myState.groups.removeIf { it.id == groupId }
        if (myState.groups.isEmpty()) {
            myState.groups.add(RequestGroup(name = "My Requests"))
        }
        notifyChanged()
    }

    /**
     * Moves a request to the end of [targetGroupId]. Returns false when the request or target group is
     * missing, or when the request already belongs to that group, so callers can treat it as a no-op.
     */
    fun moveRequest(requestId: String, targetGroupId: String): Boolean {
        val source = groupOf(requestId) ?: return false
        if (source.id == targetGroupId) return false
        val target = findGroup(targetGroupId) ?: return false
        val request = source.requests.firstOrNull { it.id == requestId } ?: return false
        source.requests.remove(request)
        target.requests.add(request)
        target.expanded = true
        notifyChanged()
        return true
    }

    fun duplicateRequest(requestId: String): HttpRequestConfig? {
        val group = groupOf(requestId) ?: return null
        val source = group.requests.firstOrNull { it.id == requestId } ?: return null
        val index = group.requests.indexOf(source)
        val copy = source.deepCopy(newId = true).apply {
            name = uniqueRequestName(group, "${source.name} copy")
            springKey = null
        }
        group.requests.add(index + 1, copy)
        notifyChanged()
        return copy
    }

    fun uniqueRequestName(group: RequestGroup, base: String): String {
        if (group.requests.none { it.name == base }) return base
        var index = 2
        while (group.requests.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    fun uniqueGroupName(base: String): String {
        if (myState.groups.none { it.name == base }) return base
        var index = 2
        while (myState.groups.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    fun findGroupByName(name: String): RequestGroup? = myState.groups.firstOrNull { it.name == name }

    fun scenarios(): List<ScenarioConfig> = myState.scenarios

    fun scenario(id: String): ScenarioConfig? = myState.scenario(id)

    /** The scenario that owns [nodeId], used by the editor tab and by diagnostics. */
    fun scenarioOf(nodeId: String): ScenarioConfig? =
        myState.scenarios.firstOrNull { scenario -> scenario.nodes.any { it.id == nodeId } }

    fun addScenario(name: String = "New Scenario"): ScenarioConfig {
        val scenario = ScenarioConfig(name = uniqueScenarioName(name))
        scenario.normalize()
        myState.scenarios.add(scenario)
        notifyChanged()
        return scenario
    }

    fun removeScenario(scenarioId: String) {
        if (myState.scenarios.removeIf { it.id == scenarioId }) {
            notifyChanged()
        }
    }

    /**
     * Copies a scenario with fresh node, edge and capture ids, then remaps the edges so the copy is
     * fully independent of the original.
     */
    fun duplicateScenario(scenarioId: String): ScenarioConfig? {
        val source = myState.scenario(scenarioId) ?: return null
        val index = myState.scenarios.indexOfFirst { it.id == scenarioId }
        val copy = source.deepCopy().apply {
            id = UUID.randomUUID().toString()
            name = uniqueScenarioName("${source.name} copy")
            val nodeIds = HashMap<String, String>()
            nodes.forEach { node ->
                val newId = UUID.randomUUID().toString()
                nodeIds[node.id] = newId
                node.id = newId
                node.outputs.forEach { output -> output.id = UUID.randomUUID().toString() }
            }
            edges.forEach { edge ->
                edge.id = UUID.randomUUID().toString()
                edge.from = nodeIds[edge.from] ?: edge.from
                edge.to = nodeIds[edge.to] ?: edge.to
            }
        }
        myState.scenarios.add(if (index >= 0) index + 1 else myState.scenarios.size, copy)
        notifyChanged()
        return copy
    }

    fun renameScenario(scenarioId: String, name: String): Boolean {
        val scenario = myState.scenario(scenarioId) ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val next = if (myState.scenarios.any { it.id != scenarioId && it.name == trimmed }) {
            uniqueScenarioName(trimmed)
        } else {
            trimmed
        }
        if (scenario.name == next) return false
        scenario.name = next
        notifyChanged()
        return true
    }

    fun uniqueScenarioName(base: String): String {
        val requested = base.trim().ifEmpty { "New Scenario" }
        if (myState.scenarios.none { it.name == requested }) return requested
        var index = 2
        while (myState.scenarios.any { it.name == "$requested $index" }) index++
        return "$requested $index"
    }

    fun addHistory(entry: RequestHistoryEntry) {
        myState.history.add(0, entry)
        while (myState.history.size > SmartRequestState.MAX_HISTORY) {
            myState.history.removeAt(myState.history.lastIndex)
        }
        notifyChanged()
    }

    fun clearHistory() {
        myState.history.clear()
        notifyChanged()
    }

    fun createGroupIfMissing(name: String): RequestGroup =
        findGroupByName(name) ?: RequestGroup().apply {
            this.name = name
            myState.groups.add(this)
        }

    /**
     * Ensures the active environment is valid after external state changes and notifies listeners.
     */
    fun normalizeAndNotify() {
        myState.normalize()
        notifyChanged()
    }

    companion object {
        fun getInstance(project: Project): SmartRequestProjectService = project.service()
    }
}
