package app.lookout.server

import app.lookout.core.Args
import app.lookout.core.CallContext
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import app.lookout.core.LookoutJson
import app.lookout.core.Tools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * A stateless MCP server over Streamable HTTP: every POST carries one JSON-RPC message (or a batch)
 * and gets one JSON response. There are no sessions. The only server-initiated message is
 * notifications/tools/list_changed, on a GET event stream, when a client's last tool list is out of date.
 */
class McpHandler(private val core: LookoutCore) {
    /** An open event stream of [client]. [stop] completes when a newer stream of the same client replaces it. */
    class Stream(val client: String) {
        val stop = CompletableDeferred<Unit>()
    }

    /** The open streams, oldest first. */
    private val streams = mutableListOf<Stream>()

    /** Returns the JSON to send, or null for "202 Accepted, no body" (notifications only). */
    suspend fun handle(body: String, ctx: CallContext): JsonElement? {
        val msg = try {
            LookoutJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error")
        }
        return when (msg) {
            is JsonArray -> msg.mapNotNull { handleOne(it, ctx) }.let { if (it.isEmpty()) null else JsonArray(it) }
            else -> handleOne(msg, ctx)
        }
    }

    private suspend fun handleOne(msg: JsonElement, ctx: CallContext): JsonElement? {
        val obj = msg as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.content
        if (method == null) return if (id == null) null else error(id, INVALID_REQUEST, "Missing method")
        if (id == null) return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initialize(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> {
                core.clients.listedTools(ctx.key, core.tools.toolsHash)
                result(id, buildJsonObject { put("tools", core.tools.toolList) })
            }
            "tools/call" -> callTool(id, params, ctx)
            "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }
            putJsonObject("serverInfo") {
                put("name", "lookout")
                put("title", "Lookout camera (${core.deviceName})")
                put("version", LookoutCore.SERVER_VERSION)
            }
            put("instructions", Tools.SERVER_INSTRUCTIONS)
        }
    }

    /**
     * Opens an event stream for [client], or returns null when [MAX_STREAMS] are open. A client with
     * [MAX_STREAMS_PER_CLIENT] open streams gets a new one in place of its oldest: a client opens a new stream when
     * it lost the old one, and a lost connection can take minutes to fail a write.
     */
    @Synchronized
    fun openStream(client: String): Stream? {
        val mine = streams.filter { it.client == client }
        if (mine.size >= MAX_STREAMS_PER_CLIENT) {
            streams.remove(mine.first())
            mine.first().stop.complete(Unit)
        } else if (streams.size >= MAX_STREAMS) {
            return null
        }
        return Stream(client).also { streams += it }
    }

    @Synchronized
    fun closeStream(stream: Stream) {
        streams.remove(stream)
    }

    /** The messages a new event stream of [client] starts with: list_changed when the tool list it last loaded is out of date. */
    fun streamMessages(client: String): List<JsonObject> =
        if (core.clients.toolsChanged(client, core.tools.toolsHash)) listOf(LIST_CHANGED) else emptyList()

    private suspend fun callTool(id: JsonElement, params: JsonObject, ctx: CallContext): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.content ?: return error(id, INVALID_PARAMS, "Missing tool name")
        val cap = core.tools.mcpTools.firstOrNull { it.name == name } ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        val args = Args(params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        return try {
            if (cap.wakes(args)) core.agentActive()
            val r = cap.call(args, ctx)
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
        const val MAX_STREAMS_PER_CLIENT = 2
        const val MAX_STREAMS = 8
        /** How often an idle event stream gets a comment line, so proxies and clients keep it open. */
        const val KEEPALIVE_MS = 25_000L
        val LIST_CHANGED = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/tools/list_changed")
        }

        fun paramSchema(p: app.lookout.core.Param): JsonObject = p.jsonSchema()

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

