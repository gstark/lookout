package app.lookout.server

import app.lookout.core.Capability
import app.lookout.core.LookoutCore
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds /openapi.json from the same route table the server mounts. */
object OpenApi {
    private class Extra(val method: String, val path: String, val summary: String, val query: List<Pair<String, String>>, val produces: String)

    private val extras = listOf(
        Extra("get", "/v1/frames/{id}.jpg", "A buffered frame (numeric id), a composite (c12) or a baseline (by name).", listOf("width" to "integer", "grid" to "string"), "image/jpeg"),
        Extra("get", "/v1/events", "Event log, optionally long-polled.", listOf("since_id" to "integer", "wait_ms" to "integer", "types" to "string"), "application/json"),
        Extra("get", "/v1/stream.mjpeg", "Live view for humans in a browser.", listOf("fps" to "number", "width" to "integer"), "multipart/x-mixed-replace"),
        Extra("get", "/v1/setup.svg", "QR code holding {url, token}.", emptyList(), "image/svg+xml"),
        Extra("post", "/mcp", "MCP Streamable HTTP endpoint (stateless JSON-RPC).", emptyList(), "application/json"),
    )

    fun build(routes: List<RestRoute>): JsonObject = buildJsonObject {
        put("openapi", "3.1.0")
        putJsonObject("info") {
            put("title", "Lookout")
            put("version", LookoutCore.SERVER_VERSION)
        }
        putJsonObject("components") {
            putJsonObject("securitySchemes") { putJsonObject("bearer") { put("type", "http"); put("scheme", "bearer") } }
        }
        putJsonArray("security") { addJsonObject { putJsonArray("bearer") {} } }
        putJsonObject("paths") {
            val byPath = routes.groupBy { it.path }
            byPath.forEach { (path, rs) ->
                putJsonObject(path) {
                    rs.forEach { r -> put(r.method.value.lowercase(), operation(r)) }
                }
            }
            extras.groupBy { it.path }.forEach { (path, es) ->
                putJsonObject(path) {
                    es.forEach { e ->
                        putJsonObject(e.method) {
                            put("summary", e.summary)
                            putJsonArray("parameters") {
                                e.query.forEach { (n, t) -> addJsonObject { put("name", n); put("in", "query"); putJsonObject("schema") { put("type", t) } } }
                            }
                            putJsonObject("responses") { putJsonObject("200") { put("description", "OK"); putJsonObject("content") { putJsonObject(e.produces) {} } } }
                        }
                    }
                }
            }
        }
    }

    private fun operation(r: RestRoute): JsonObject = buildJsonObject {
        val c = r.capability
        put("operationId", "${r.method.value.lowercase()}_${c.name}${r.fixed["action"]?.let { "_$it" } ?: ""}")
        put("summary", c.description)
        val inQuery = r.method == HttpMethod.Get || r.method == HttpMethod.Delete
        putJsonArray("parameters") {
            r.pathArgs.keys.forEach { p -> addJsonObject { put("name", p); put("in", "path"); put("required", true); putJsonObject("schema") { put("type", "string") } } }
            if (inQuery) r.params.forEach { p ->
                addJsonObject {
                    put("name", p.name)
                    put("in", "query")
                    put("required", p.required)
                    put("description", p.description)
                    put("schema", McpHandler.paramSchema(p))
                }
            }
        }
        if (!inQuery && r.params.isNotEmpty()) {
            putJsonObject("requestBody") {
                putJsonObject("content") {
                    putJsonObject("application/json") {
                        putJsonObject("schema") {
                            put("type", "object")
                            putJsonObject("properties") { r.params.forEach { p -> put(p.name, McpHandler.paramSchema(p)) } }
                            val req = r.params.filter { it.required }
                            if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it.name) } }
                        }
                    }
                }
            }
        }
        putJsonObject("responses") {
            putJsonObject("200") {
                put("description", if (c.kind == Capability.Kind.IMAGE) "JPEG, with metadata JSON in the X-Lookout-Meta header (or {meta, image_b64} with format=json)" else "JSON")
                putJsonObject("content") { putJsonObject(if (c.kind == Capability.Kind.IMAGE) "image/jpeg" else "application/json") {} }
            }
            putJsonObject("default") { put("description", "{\"error\":{\"code\",\"message\"}}") }
        }
    }
}
