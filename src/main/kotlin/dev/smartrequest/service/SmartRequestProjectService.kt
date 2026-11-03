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
