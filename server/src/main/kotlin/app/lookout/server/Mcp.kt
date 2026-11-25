package app.lookout.server

import app.lookout.core.Args
import app.lookout.core.CallContext
import app.lookout.core.Capability
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import app.lookout.core.LookoutJson
import app.lookout.core.Tools
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * A stateless MCP server over Streamable HTTP: every POST carries one JSON-RPC message (or a batch)
 * and gets one JSON response. There are no sessions and no server-initiated messages.
 */
class McpHandler(private val core: LookoutCore) {

    /** Returns the JSON to send, or null for "202 Accepted, no body" (notifications only). */
    suspend fun handle(body: String, remote: String?): JsonElement? {
        val msg = try {
            LookoutJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error")
        }
        return when (msg) {
            is JsonArray -> msg.mapNotNull { handleOne(it, remote) }.let { if (it.isEmpty()) null else JsonArray(it) }
            else -> handleOne(msg, remote)
        }
    }

    private suspend fun handleOne(msg: JsonElement, remote: String?): JsonElement? {
        val obj = msg as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.content
        if (method == null) return if (id == null) null else error(id, INVALID_REQUEST, "Missing method")
        if (id == null) return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(core.tools.mcpTools.map(::toolJson))) })
            "tools/call" -> callTool(id, params, remote)
            "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "lookout")
                put("title", "Lookout camera (${core.deviceName})")
                put("version", LookoutCore.SERVER_VERSION)
            }
            put("instructions", Tools.SERVER_INSTRUCTIONS)
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject, remote: String?): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.content ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val cap = core.tools.mcpTools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            if (cap.wakes) core.agentActive()
            val r = cap.call(Args(args), CallContext("mcp", remote))
            result(id, buildJsonObject {
                put("content", buildJsonArray {
                    r.image?.let { img ->
                        addJsonObject {
                            put("type", "image")
                            put("data", Base64.getEncoder().encodeToString(img.jpeg))
                            put("mimeType", "image/jpeg")
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", r.json.toString())
                    }
                })
                put("isError", false)
            })
        } catch (e: LookoutError) {
            toolError(id, e.code, e.message ?: e.code)
        } catch (e: SerializationException) {
            toolError(id, "invalid_param", e.message ?: "Invalid arguments")
        } catch (e: IllegalArgumentException) {
            toolError(id, "invalid_param", e.message ?: "Invalid arguments")
        }
    }

    private fun toolError(id: JsonElement, code: String, message: String) = result(id, buildJsonObject {
        put("content", buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", errorJson(code, message).toString())
            }
        })
        put("isError", true)
    })

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        fun toolJson(c: Capability): JsonObject = buildJsonObject {
            put("name", c.name)
            put("description", c.description)
            put("inputSchema", inputSchema(c))
        }

        fun inputSchema(c: Capability): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                c.params.forEach { p -> put(p.name, paramSchema(p)) }
            }
            val req = c.params.filter { it.required }
            if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it.name) } }
            put("additionalProperties", false)
        }

        fun paramSchema(p: app.lookout.core.Param): JsonObject {
            p.schema?.let { s -> return JsonObject(s + ("description" to JsonPrimitive((s["description"] as? JsonPrimitive)?.content ?: p.description))) }
            return buildJsonObject {
                put("type", p.type.jsonType)
                put("description", p.description)
                if (p.type == app.lookout.core.PType.STRING_ARRAY) putJsonObject("items") { put("type", "string") }
                p.enum?.let { e -> putJsonArray("enum") { e.forEach { add(it) } } }
            }
        }

        private fun result(id: JsonElement, result: JsonElement) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
    }
}

fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

