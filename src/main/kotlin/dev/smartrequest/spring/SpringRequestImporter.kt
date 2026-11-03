package dev.smartrequest.spring

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.service.SmartRequestProjectService

data class SpringImportResult(
    val groupName: String,
    val groupId: String,
    val firstRequestId: String?,
    val added: Int,
    val updated: Int
)

fun interface SpringRequestsImportedListener {
    fun endpointsImported(result: SpringImportResult)
}

object SmartRequestTopics {
    val SPRING_REQUESTS_IMPORTED: Topic<SpringRequestsImportedListener> = Topic.create(
        "Smart Request Spring endpoints imported",
        SpringRequestsImportedListener::class.java
    )
}

/**
 * Applies generated requests to the project state. Existing imports are updated by their stable Spring key,
 * while user-edited parameter values, extra parameters, headers, and bodies are preserved.
 */
object SpringRequestImporter {

    fun apply(project: Project, payload: SpringGenerationPayload): SpringImportResult {
        val service = project.service<SmartRequestProjectService>()
        val group = service.findGroupByName(payload.groupName)
            ?: service.createGroupIfMissing(payload.groupName)
        group.expanded = true
        val existing = group.requests
            .filter { it.springKey != null }
            .associateBy { it.springKey }
        var added = 0
        var updated = 0

        payload.requests.forEach { imported ->
            val request = existing[imported.springKey]
            if (request == null) {
                imported.name = service.uniqueRequestName(group, imported.name)
                group.requests.add(imported)
                added++
            } else {
                mergeInto(request, imported)
                updated++
            }
        }

        service.notifyChanged()
        val result = SpringImportResult(
            groupName = group.name,
            groupId = group.id,
            firstRequestId = payload.requests
                .firstOrNull()
                ?.let { first -> group.requests.firstOrNull { it.springKey == first.springKey }?.id },
            added = added,
            updated = updated
        )
        project.messageBus.syncPublisher(SmartRequestTopics.SPRING_REQUESTS_IMPORTED).endpointsImported(result)
        return result
    }

    private fun mergeInto(target: HttpRequestConfig, imported: HttpRequestConfig) {
        target.name = imported.name
        target.method = imported.method
        target.url = imported.url
        target.springKey = imported.springKey

        val existingPathValues = target.pathParams.associateBy { it.key }
        target.pathParams.clear()
        imported.pathParams.forEach { pathParam ->
            target.pathParams.add(
                KeyValue.of(pathParam.key, existingPathValues[pathParam.key]?.value.orEmpty())
            )
        }

        val knownParams = target.params.map { it.key }.toMutableSet()
        imported.params.forEach { parameter ->
            if (parameter.key !in knownParams) {
                target.params.add(parameter.deepCopy())
                knownParams.add(parameter.key)
            }
        }

        if (target.bodyTypeEnum() == BodyType.NONE && target.body.isBlank()) {
            target.bodyType = imported.bodyType
            target.body = imported.body
        }
    }
}
