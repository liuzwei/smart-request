package dev.smartrequest.scenario

import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import dev.smartrequest.service.SmartRequestProjectService
import java.util.UUID

/**
 * Applies a decoded [ScenarioBundle] to the project: creates the referenced requests (new ids,
 * preserving their group names) and the scenario that points at them. The exported request keys are
 * only used to remap the nodes; nothing from the file keeps its original id, so importing the same
 * file twice yields two independent copies.
 */
object ScenarioImporter {

    const val DEFAULT_GROUP = "Imported Requests"

    fun apply(service: SmartRequestProjectService, bundle: ScenarioBundle): ScenarioConfig {
        val scenario = ScenarioConfig(
            name = service.uniqueScenarioName(bundle.name.ifBlank { "Imported Scenario" }),
            notes = bundle.notes,
            maxParallel = bundle.maxParallel,
            parameters = bundle.parameters.map { it.deepCopy() }.toMutableList()
        )

        val requestIds = LinkedHashMap<String, String>()
        bundle.requests.forEach { spec ->
            val groupName = spec.group.trim().ifBlank { DEFAULT_GROUP }
            val group = service.findGroupByName(groupName) ?: service.createGroupIfMissing(groupName)
            val request = spec.request.deepCopy(newId = true).apply {
                name = service.uniqueRequestName(group, name)
                springKey = null
                normalize()
            }
            group.requests.add(request)
            group.expanded = true
            if (spec.key.isNotBlank()) requestIds[spec.key] = request.id
        }

        val nodeIds = LinkedHashMap<String, String>()
        bundle.nodes.forEach { spec ->
            val node = ScenarioNode(
                id = UUID.randomUUID().toString(),
                kind = spec.kind,
                requestId = spec.requestKey?.let { requestIds[it] },
                name = spec.name,
                x = spec.x,
                y = spec.y,
                expectStatus = spec.expectStatus,
                timeoutMs = spec.timeoutMs,
                retries = spec.retries,
                outputs = spec.outputs.map { output -> output.deepCopy().apply { id = UUID.randomUUID().toString() } }
                    .toMutableList()
            )
            nodeIds[spec.id] = node.id
            scenario.nodes.add(node)
        }
        bundle.edges.forEach { edge ->
            val from = nodeIds[edge.from] ?: return@forEach
            val to = nodeIds[edge.to] ?: return@forEach
            scenario.edges.add(ScenarioEdge(from = from, to = to))
        }

        scenario.normalize()
        ScenarioGraph.syncTerminals(scenario)
        service.state().scenarios.add(scenario)
        service.notifyChanged()
        return scenario
    }
}
