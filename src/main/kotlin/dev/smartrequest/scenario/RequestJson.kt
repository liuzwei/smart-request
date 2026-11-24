package dev.smartrequest.scenario

import dev.smartrequest.extract.JsonNode
import dev.smartrequest.extract.JsonReader
import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue
import dev.smartrequest.model.RequestGroup
import dev.smartrequest.model.ResponseExtractor

/**
 * Self-contained JSON export/import for requests and their groups. Uses the same [JsonText] writer
 * as [dev.smartrequest.scenario.ScenarioJson], with no third-party JSON library.
 */
object RequestJson {

    const val FORMAT = "smart-request-requests"
    const val VERSION = 1

    // ---------------------------------------------------------------- encoding

    fun encode(groups: List<RequestGroup>): String {
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "groups" to groups.filter { it.requests.isNotEmpty() }.map(::groupMap)
        )
        return JsonText.write(root)
    }

    fun encodeSingle(request: HttpRequestConfig, groupName: String): String {
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "groups" to listOf(
                linkedMapOf<String, Any?>(
                    "name" to groupName,
                    "requests" to listOf(requestMap(request))
                )
            )
        )
        return JsonText.write(root)
    }

    // ---------------------------------------------------------------- decoding

    fun decode(text: String): List<RequestGroup> {
        val root = JsonReader.parse(text)
            ?: throw RequestJsonException("The file is not valid JSON.")
        val top = root.objectOrThrow("the file root")
        val format = top.string("format", "")
        if (format != FORMAT) {
            throw RequestJsonException(
                "This is not a Smart Request requests file (expected \"$FORMAT\", found \"$format\")."
            )
        }
        val version = top.int("version", 1)
        if (version > VERSION) {
            throw RequestJsonException(
                "The file was created by a newer Smart Request version (format $version)."
            )
        }
        return top.arrayOrEmpty("groups").mapIndexed { index, node ->
            decodeGroup(node, index)
        }
    }

    // ---------------------------------------------------------------- group / request maps

    private fun groupMap(group: RequestGroup): Map<String, Any?> = linkedMapOf(
        "name" to group.name,
        "requests" to group.requests.map(::requestMap)
    )

    private fun requestMap(request: HttpRequestConfig): Map<String, Any?> = linkedMapOf(
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

    private fun keyValueMap(kv: KeyValue): Map<String, Any?> = linkedMapOf(
        "key" to kv.key,
        "value" to kv.value,
        "enabled" to kv.enabled
    )

    private fun outputMap(output: ResponseExtractor): Map<String, Any?> = linkedMapOf(
        "variable" to output.variable,
        "source" to output.source,
        "enabled" to output.enabled,
        "required" to output.required
    )

    // ---------------------------------------------------------------- decoding helpers

    private fun decodeGroup(node: JsonNode, index: Int): RequestGroup {
        val entry = node.objectOrThrow("groups[$index]")
        val group = RequestGroup(
            name = entry.string("name", "Imported").ifBlank { "Imported" },
            expanded = true
        )
        val requests = entry.arrayOrEmpty("requests").mapIndexed { i, item ->
            decodeRequest(item, "groups[$index].requests[$i]")
        }
        group.requests.addAll(requests)
        return group
    }

    private fun decodeRequest(node: JsonNode, context: String): HttpRequestConfig {
        val entry = node.objectOrThrow(context)
        return HttpRequestConfig(
            name = entry.string("name", "New Request"),
            method = entry.string("method", "GET"),
            url = entry.string("url", "{{baseUrl}}/"),
            params = entry.arrayOrEmpty("params").mapIndexed { i, item ->
                decodeKeyValue(item, "$context.params[$i]")
            }.toMutableList(),
            headers = entry.arrayOrEmpty("headers").mapIndexed { i, item ->
                decodeKeyValue(item, "$context.headers[$i]")
            }.toMutableList(),
            pathParams = entry.arrayOrEmpty("pathParams").mapIndexed { i, item ->
                decodeKeyValue(item, "$context.pathParams[$i]")
            }.toMutableList(),
            bodyType = entry.string("bodyType", BodyType.NONE.name),
            body = entry.string("body", ""),
            extractors = entry.arrayOrEmpty("extractors").mapIndexed { i, item ->
                decodeOutput(item, "$context.extractors[$i]")
            }.toMutableList(),
            useGlobalHeaders = entry.bool("useGlobalHeaders", true),
            useGlobalParams = entry.bool("useGlobalParams", true)
        ).apply { normalize() }
    }

    private fun decodeKeyValue(node: JsonNode, context: String): KeyValue {
        val entry = node.objectOrThrow(context)
        return KeyValue(
            key = entry.string("key", ""),
            value = entry.string("value", ""),
            enabled = entry.bool("enabled", true)
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

    // ---------------------------------------------------------------- JSON helpers

    private fun JsonNode.objectOrThrow(context: String): Map<String, JsonNode> =
        (this as? JsonNode.Obj)?.entries
            ?: throw RequestJsonException("$context must be a JSON object.")

    private fun Map<String, JsonNode>.string(key: String, default: String): String =
        (this[key] as? JsonNode.Str)?.value ?: default

    private fun Map<String, JsonNode>.int(key: String, default: Int): Int =
        ((this[key] as? JsonNode.Num)?.raw?.toDoubleOrNull()?.toInt()) ?: default

    private fun Map<String, JsonNode>.bool(key: String, default: Boolean): Boolean =
        (this[key] as? JsonNode.Bool)?.value ?: default

    private fun Map<String, JsonNode>.arrayOrEmpty(key: String): List<JsonNode> {
        val node = this[key] ?: return emptyList()
        if (node === JsonNode.Null) return emptyList()
        return (node as? JsonNode.Arr)?.items
            ?: throw RequestJsonException("\"$key\" must be a JSON array.")
    }
}

class RequestJsonException(message: String) : Exception(message)