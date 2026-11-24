package dev.smartrequest.scenario

import dev.smartrequest.model.RequestGroup
import dev.smartrequest.service.SmartRequestProjectService

/**
 * Applies decoded request groups to the project.
 *
 * Importing is strictly one-file-one-batch: every request in [groups] is added exactly once, with a
 * fresh id, so a file with four requests adds four requests. Groups that already exist are merged
 * into by name (the usual "import my colleague's collection into my project" case).
 */
object RequestImporter {

    /**
     * Applies [groups] to [service] and returns how many requests were added. Passing the same
     * decoded groups twice adds them twice by design (that is a genuine second import); the UI
     * guards against accidental double invocation instead, see [SmartRequestImportGuard].
     */
    fun apply(service: SmartRequestProjectService, groups: List<RequestGroup>): Int {
        var count = 0
        groups.forEach { source ->
            val target = service.findGroupByName(source.name) ?: service.createGroupIfMissing(source.name)
            source.requests.forEach { request ->
                val copy = request.deepCopy(newId = true).apply {
                    name = service.uniqueRequestName(target, name)
                    springKey = null
                    normalize()
                }
                target.requests.add(copy)
                count++
            }
            target.expanded = true
        }
        if (count > 0) service.notifyChanged()
        return count
    }
}

/**
 * Rejects an import whose file content was already imported moments ago. Two clicks on the Import
 * button (or a double-click on a file) must not add the same batch twice; a deliberate second import
 * later is still allowed.
 */
object SmartRequestImportGuard {

    private const val WINDOW_MS = 1_500L

    private var lastContentHash: Int? = null
    private var lastAppliedAt = 0L

    /** Returns true when this content should be imported, and remembers it. */
    fun shouldImport(content: String, now: Long = System.currentTimeMillis()): Boolean {
        val hash = content.hashCode()
        if (hash == lastContentHash && now - lastAppliedAt < WINDOW_MS) return false
        lastContentHash = hash
        lastAppliedAt = now
        return true
    }

    fun reset() {
        lastContentHash = null
        lastAppliedAt = 0L
    }
}