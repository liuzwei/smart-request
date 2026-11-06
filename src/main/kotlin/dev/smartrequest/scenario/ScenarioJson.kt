package dev.smartrequest.scenario

import dev.smartrequest.extract.JsonNode
import dev.smartrequest.extract.JsonReader
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.ResponseExtractor
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.model.ScenarioEdge
import dev.smartrequest.model.ScenarioNode
import java.util.UUID

/** A self-contained scenario file: the flow plus the requests it references. */
data class ScenarioBundle(
    val name: String,
    val notes: String,
    val maxParallel: Int,
    val parameters: List<KeyValue>,
    val requests: List<RequestSpec>,
    val nodes: List<NodeSpec>,
    val edges: List<EdgeSpec>
)

data class RequestSpec(
    val key: String,
    val group: String,
    val request: HttpRequestConfig
)

data class NodeSpec(
    val id: String,
    val kind: String,
    val requestKey: String?,
    val name: String,
    val x: Int,
    val y: Int,
    val expectStatus: String,
    val timeoutMs: Int?,
    val retries: Int,
    val outputs: List<ResponseExtractor>
)

data class EdgeSpec(val from: String, val to: String)

class ScenarioJsonException(message: String) : Exception(message)

/**
 * JSON import/export for scenarios. The file is deliberately self-contained: it carries the
 * referenced requests (with their group names), so a scenario can be shared with someone whose
 * project does not have those requests yet.
 *
 * No JSON library is used on purpose; the project already ships a small reader for the Extract
 * feature, and the writer below is a few lines.
 */
object ScenarioJson {

    const val FORMAT = "smart-request-scenario"
    const val VERSION = 1

    fun encode(
        scenario: ScenarioConfig,
        requestProvider: (String) -> HttpRequestConfig?,
        groupProvider: (String) -> String?
    ): String {
        val requestIds = scenario.nodes
            .filter { it.isRequestNode() }
            .mapNotNull { it.requestId }
            .distinct()
        val requests = requestIds.mapNotNull { id ->
            requestProvider(id)?.let { request ->
                RequestSpec(key = id, group = groupProvider(id).orEmpty(), request = request)
            }
        }
        val exportedKeys = requests.mapTo(HashSet()) { it.key }
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "name" to scenario.name,
            "notes" to scenario.notes,
            "maxParallel" to scenario.maxParallel,
            "parameters" to scenario.parameters.map(::keyValueMap),
            "requests" to requests.map(::requestMap),
            "nodes" to scenario.nodes.map { node -> nodeMap(node, exportedKeys) },
            "edges" to scenario.edges.map { edge ->
                linkedMapOf("from" to edge.from, "to" to edge.to)
            }
        )
        return JsonText.write(root)
    }

    fun decode(text: String): ScenarioBundle {
        val root = JsonReader.parse(text)
            ?: throw ScenarioJsonException("The file is not valid JSON.")
        val top = root.objectOrThrow("the file root")
        val format = top.string("format", "")
        if (format != FORMAT) {
            throw ScenarioJsonException(
                "This is not a Smart Request scenario file (expected \"$FORMAT\", found \"$format\")."
            )
        }
        val version = top.int("version", 1)
        if (version > VERSION) {
            throw ScenarioJsonException(
                "The file was created by a newer Smart Request version (format $version)."
            )
        }

        val parameters = top.arrayOrEmpty("parameters").mapIndexed { index, node ->
            decodeKeyValue(node, "parameters[$index]")
        }
        val requests = top.arrayOrEmpty("requests").mapIndexed { index, node ->
            decodeRequest(node, index)
        }
        val nodes = top.arrayOrEmpty("nodes").mapIndexed { index, node ->
            decodeNode(node, index)
        }
        val edges = top.arrayOrEmpty("edges").mapIndexed { index, node ->
            decodeEdge(node, index)
        }
        return ScenarioBundle(
            name = top.string("name", "Imported Scenario").ifBlank { "Imported Scenario" },
            notes = top.string("notes", ""),
            maxParallel = top.int("maxParallel", ScenarioConfig.DEFAULT_MAX_PARALLEL),
            parameters = parameters,
            requests = requests,
            nodes = nodes,
            edges = edges
        )
    }

    // ------------------------------------------------------------------ encoding

    private fun keyValueMap(value: KeyValue): Map<String, Any?> = linkedMapOf(
        "key" to value.key,
        "value" to value.value,
        "enabled" to value.enabled
    )

    private fun outputMap(output: ResponseExtractor): Map<String, Any?> = linkedMapOf(
        "variable" to output.variable,
        "source" to output.source,
        "enabled" to output.enabled,
        "required" to output.required
    )

    private fun requestMap(spec: RequestSpec): Map<String, Any?> {
        val request = spec.request
        return linkedMapOf(
            "key" to spec.key,
            "group" to spec.group,
            "request" to linkedMapOf(
                "name" to request.name,
                "method" to request.method,
                "url" to request.url,
                "params" to request.params.map(::keyValueMap),
                "headers" to request.headers.map(::keyValueMap),
                "pathParams" to request.pathParams.map(::keyValueMap),
                "bodyType" to request.bodyType,
                "body" to request.body,
                "extractors" to request.extractors.map(::outputMap),
                "useGlobalHeaders" to request.useGlobalHeaders,
                "useGlobalParams" to request.useGlobalParams
            )
        )
    }

    private fun nodeMap(node: ScenarioNode, exportedRequests: Set<String>): Map<String, Any?> = linkedMapOf(
        "id" to node.id,
        "kind" to node.kind,
        "request" to node.requestId?.takeIf { it in exportedRequests },
        "name" to node.name,
        "x" to node.x,
        "y" to node.y,
        "expectStatus" to node.expectStatus,
        "timeoutMs" to node.timeoutMs,
        "retries" to node.retries,
        "outputs" to node.outputs.map(::outputMap)
    )

    // ------------------------------------------------------------------ decoding

    private fun decodeRequest(node: JsonNode, index: Int): RequestSpec {
        val entry = node.objectOrThrow("requests[$index]")
        val request = (entry["request"] ?: throw ScenarioJsonException("requests[$index] has no request."))
            .objectOrThrow("requests[$index].request")
        return RequestSpec(
            key = entry.string("key", ""),
            group = entry.string("group", ""),
            request = HttpRequestConfig(
                name = request.string("name", "New Request"),
                method = request.string("method", "GET"),
                url = request.string("url", "{{baseUrl}}/"),
                params = request.arrayOrEmpty("params").mapIndexed { i, item ->
                    decodeKeyValue(item, "requests[$index].params[$i]")
                }.toMutableList(),
                headers = request.arrayOrEmpty("headers").mapIndexed { i, item ->
                    decodeKeyValue(item, "requests[$index].headers[$i]")
                }.toMutableList(),
                pathParams = request.arrayOrEmpty("pathParams").mapIndexed { i, item ->
                    decodeKeyValue(item, "requests[$index].pathParams[$i]")
                }.toMutableList(),
                bodyType = request.string("bodyType", "NONE"),
                body = request.string("body", ""),
                extractors = request.arrayOrEmpty("extractors").mapIndexed { i, item ->
                    decodeOutput(item, "requests[$index].extractors[$i]")
                }.toMutableList(),
                useGlobalHeaders = request.bool("useGlobalHeaders", true),
                useGlobalParams = request.bool("useGlobalParams", true)
            ).apply { normalize() }
        )
    }

    private fun decodeNode(node: JsonNode, index: Int): NodeSpec {
        val entry = node.objectOrThrow("nodes[$index]")
        val id = entry.string("id", "").ifBlank { UUID.randomUUID().toString() }
        val requestKey = entry["request"]?.stringOrNull()?.takeIf { it.isNotBlank() }
        return NodeSpec(
            id = id,
            kind = entry.string("kind", ScenarioNode.KIND_REQUEST),
            requestKey = requestKey,
            name = entry.string("name", ""),
            x = entry.int("x", 0),
            y = entry.int("y", 0),
            expectStatus = entry.string("expectStatus", ""),
            timeoutMs = entry.intOrNull("timeoutMs"),
            retries = entry.int("retries", 0),
            outputs = entry.arrayOrEmpty("outputs").mapIndexed { i, item ->
                decodeOutput(item, "nodes[$index].outputs[$i]")
            }
        )
    }

    private fun decodeOutput(node: JsonNode, context: String): ResponseExtractor {
        val entry = node.objectOrThrow(context)
        return ResponseExtractor(
            variable = entry.string("variable", ""),
            source = entry.string("source", ""),
            enabled = entry.bool("enabled", true),
            required = entry.bool("required", true)
        )
    }

    private fun decodeKeyValue(node: JsonNode, context: String): KeyValue {
        val entry = node.objectOrThrow(context)
        return KeyValue(
            key = entry.string("key", ""),
            value = entry.string("value", ""),
            enabled = entry.bool("enabled", true)
        )
    }

    private fun decodeEdge(node: JsonNode, index: Int): EdgeSpec {
        val entry = node.objectOrThrow("edges[$index]")
        val from = entry.string("from", "")
        val to = entry.string("to", "")
        if (from.isBlank() || to.isBlank()) {
            throw ScenarioJsonException("edges[$index] must have both \"from\" and \"to\".")
        }
        return EdgeSpec(from, to)
    }

    // ------------------------------------------------------------------ JSON helpers

    private fun JsonNode.objectOrThrow(context: String): Map<String, JsonNode> =
        (this as? JsonNode.Obj)?.entries
            ?: throw ScenarioJsonException("$context must be a JSON object.")

    private fun JsonNode.stringOrNull(): String? = (this as? JsonNode.Str)?.value

    private fun Map<String, JsonNode>.string(key: String, default: String): String =
        (this[key] as? JsonNode.Str)?.value ?: default

    private fun Map<String, JsonNode>.int(key: String, default: Int): Int = intOrNull(key) ?: default

    private fun Map<String, JsonNode>.intOrNull(key: String): Int? =
        (this[key] as? JsonNode.Num)?.raw?.toDoubleOrNull()?.toInt()

    private fun Map<String, JsonNode>.bool(key: String, default: Boolean): Boolean =
        (this[key] as? JsonNode.Bool)?.value ?: default

    private fun Map<String, JsonNode>.arrayOrEmpty(key: String): List<JsonNode> {
        val node = this[key] ?: return emptyList()
        if (node === JsonNode.Null) return emptyList()
        return (node as? JsonNode.Arr)?.items
            ?: throw ScenarioJsonException("\"$key\" must be a JSON array.")
    }
}

/** Minimal pretty JSON writer; keeps the file diff-friendly and library-free. */
private object JsonText {
    fun write(value: Any?): String = buildString {
        appendValue(value, 0)
        append('\n')
    }

    private fun StringBuilder.appendValue(value: Any?, depth: Int) {
        when (value) {
            null -> append("null")
            is String -> append(quote(value))
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> appendObject(value, depth)
            is Iterable<*> -> appendArray(value, depth)
            else -> append(quote(value.toString()))
        }
    }

    private fun StringBuilder.appendObject(value: Map<*, *>, depth: Int) {
        if (value.isEmpty()) {
            append("{}")
            return
        }
        append("{\n")
        value.entries.forEachIndexed { index, entry ->
            appendIndent(depth + 1)
            append(quote(entry.key.toString()))
            append(": ")
            appendValue(entry.value, depth + 1)
            if (index < value.size - 1) append(',')
            append('\n')
        }
        appendIndent(depth)
        append('}')
    }

    private fun StringBuilder.appendArray(value: Iterable<*>, depth: Int) {
        val items = value.toList()
        if (items.isEmpty()) {
            append("[]")
            return
        }
        append("[\n")
        items.forEachIndexed { index, item ->
            appendIndent(depth + 1)
            appendValue(item, depth + 1)
            if (index < items.size - 1) append(',')
            append('\n')
        }
        appendIndent(depth)
        append(']')
    }

    private fun StringBuilder.appendIndent(depth: Int) {
        repeat(depth) { append("  ") }
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (character < ' ') {
                        builder.append("\\u")
                        builder.append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(character)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
