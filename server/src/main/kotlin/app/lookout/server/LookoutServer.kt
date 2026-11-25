package app.lookout.server

import app.lookout.core.Args
import app.lookout.core.CallContext
import app.lookout.core.CapResult
import app.lookout.core.Capability
import app.lookout.core.Compositor
import app.lookout.core.GridSpec
import app.lookout.core.isoTime
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import app.lookout.core.LookoutJson
import app.lookout.core.Param
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** A REST route backed by a capability. [fixed] args are set by the route (for example action=list). */
class RestRoute(
    val method: HttpMethod,
    val path: String,
    val capability: Capability,
    val fixed: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    /** Path parameter name -> capability argument name. */
    val pathArgs: Map<String, String> = emptyMap(),
) {
    val params: List<Param> get() = capability.params.filter { it.name !in fixed && it.name !in pathArgs.values }
}

class LookoutServer(
    private val core: LookoutCore,
    private val tokens: TokenStore,
    private val port: Int = core.settings.value.port,
    /** Accepts the local (server-side) address of a connection. Android allows Wi-Fi, loopback and, with the setting, Tailscale. */
    private val allowLocal: (String) -> Boolean = { true },
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val _activeCalls = MutableStateFlow(0)

    /** The number of requests in progress. Android holds its wake lock while this is above zero. */
    val activeCalls: StateFlow<Int> = _activeCalls.asStateFlow()

    fun start(wait: Boolean = false) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            intercept(ApplicationCallPipeline.Setup) {
                _activeCalls.update { it + 1 }
                try {
                    proceed()
                } finally {
                    _activeCalls.update { it - 1 }
                }
            }
            lookoutModule(core, tokens, allowLocal)
        }.start(wait)
    }

    fun stop() {
        engine?.stop(500, 2_000)
        engine = null
    }
}

fun restRoutes(core: LookoutCore): List<RestRoute> {
    val t = core.tools
    return listOf(
        RestRoute(HttpMethod.Get, "/v1/describe", t.describe),
        RestRoute(HttpMethod.Get, "/v1/status", t.status),
        RestRoute(HttpMethod.Get, "/v1/frame", t.look, defaults = mapOf("fresh" to "false")),
        RestRoute(HttpMethod.Post, "/v1/capture", t.capture),
        RestRoute(HttpMethod.Post, "/v1/wait", t.waitFor),
        RestRoute(HttpMethod.Get, "/v1/history", t.history),
        RestRoute(HttpMethod.Get, "/v1/recordings", t.record, fixed = mapOf("action" to "list")),
        RestRoute(HttpMethod.Post, "/v1/recordings", t.record, fixed = mapOf("action" to "start")),
        RestRoute(HttpMethod.Post, "/v1/recordings/mark", t.record, fixed = mapOf("action" to "mark")),
        RestRoute(HttpMethod.Post, "/v1/recordings/stop", t.record, fixed = mapOf("action" to "stop")),
        RestRoute(HttpMethod.Get, "/v1/recordings/{id}/clip", t.clip, pathArgs = mapOf("id" to "recording_id")),
        RestRoute(HttpMethod.Post, "/v1/diff", t.diff),
        RestRoute(HttpMethod.Get, "/v1/rois", t.roi, fixed = mapOf("action" to "list")),
        RestRoute(HttpMethod.Put, "/v1/rois/{name}", t.roi, fixed = mapOf("action" to "set"), pathArgs = mapOf("name" to "name")),
        RestRoute(HttpMethod.Delete, "/v1/rois/{name}", t.roi, fixed = mapOf("action" to "delete"), pathArgs = mapOf("name" to "name")),
        RestRoute(HttpMethod.Get, "/v1/baselines", t.baseline, fixed = mapOf("action" to "list")),
        RestRoute(HttpMethod.Post, "/v1/baselines/{name}", t.baseline, fixed = mapOf("action" to "save"), pathArgs = mapOf("name" to "name")),
        RestRoute(HttpMethod.Delete, "/v1/baselines/{name}", t.baseline, fixed = mapOf("action" to "delete"), pathArgs = mapOf("name" to "name")),
        RestRoute(HttpMethod.Put, "/v1/camera", t.camera),
        RestRoute(HttpMethod.Post, "/v1/say", t.say),
        RestRoute(HttpMethod.Post, "/v1/ask", t.ask),
        RestRoute(HttpMethod.Get, "/v1/read", t.read),
        RestRoute(HttpMethod.Post, "/v1/point", t.point, fixed = mapOf("action" to "show")),
        RestRoute(HttpMethod.Delete, "/v1/point", t.point, fixed = mapOf("action" to "clear")),
    )
}

fun Application.lookoutModule(core: LookoutCore, tokens: TokenStore, allowLocal: (String) -> Boolean = { true }) {
    val mcp = McpHandler(core)
    val routes = restRoutes(core)
    val pairing = Pairing(core, tokens)

    intercept(ApplicationCallPipeline.Plugins) {
        val remote = call.request.local.remoteAddress
        val tailscale = core.settings.value.allowTailscale
        if (!Lan.allowsRemote(remote, tailscale) || !allowLocal(call.request.local.localAddress)) {
            val why = if (Lan.isTailnet(remote) && !tailscale) "Tailscale access is off. Turn on \"Allow Tailscale\" in Setup" else "Only private LAN addresses may connect"
            call.respondError(HttpStatusCode.Forbidden, "not_lan", why)
            finish()
            return@intercept
        }
        val path = call.request.path()
        if (path in OPEN_PATHS || path.startsWith("/v1/pair/")) return@intercept
        val access = tokens.access(call.bearer(), remote)
        if (access == Access.DENIED || access == Access.WRONG_ADDRESS) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            if (access == Access.DENIED) call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Missing or wrong bearer token")
            else call.respondError(HttpStatusCode.Unauthorized, "token_address_mismatch", "This token works only from the address that paired. Pair again from this machine")
            finish()
        }
    }

    routing {
        routes.forEach { r -> route(r.path, r.method) { handle { call.runCapability(r) } } }

        get("/v1/frames/{id}.jpg") {
            call.guarded {
                val id = call.parameters["id"]!!
                var jpeg = core.tools.frameJpeg(id) ?: throw LookoutError("frame_not_found", 404, "No frame, composite or baseline '$id'")
                val width = call.request.queryParameters["width"]?.toIntOrNull()
                val grid = call.request.queryParameters["grid"]
                if (width != null || grid != null) {
                    var img = core.compositor.fit(core.ops.decode(jpeg), width ?: Compositor.MAX_EDGE)
                    if (grid != null) img = core.compositor.grid(img, if (grid == "true") GridSpec.DEFAULT else GridSpec.parse(grid))
                    jpeg = core.ops.encodeJpeg(img, core.settings.value.jpegQuality)
                }
                call.respondBytes(jpeg, ContentType.Image.JPEG)
            }
        }

        get("/v1/recordings/{id}/video") {
            call.guarded {
                val info = core.recordings.get(call.parameters["id"]!!)
                if (info.state == "recording") throw LookoutError("recording_not_ready", 409, "Recording ${info.id} is still running")
                val file = core.recordings.file(info)
                if (!file.exists()) throw LookoutError("recording_not_found", 404, "The video file of ${info.id} is gone")
                val type = if (file.extension == "mp4") ContentType.Video.MP4 else ContentType.Application.OctetStream
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"lookout-${info.id}.${file.extension}\"")
                call.respond(LocalFileContent(file, type))
            }
        }

        get("/v1/events") {
            call.guarded {
                val q = call.request.queryParameters
                val since = q["since_id"]?.toLongOrNull() ?: 0L
                val wait = (q["wait_ms"]?.toLongOrNull() ?: 0L).coerceIn(0, 60_000)
                val types = q["types"]?.split(",")?.map { it.trim() }?.toSet()
                val events = core.events.await(since, wait).filter { types == null || it.type in types }
                call.respondJson(buildJsonObject {
                    put("events", LookoutJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(app.lookout.core.Event.serializer()), events))
                    put("latest_id", core.events.latestId.value)
                })
            }
        }

        get("/v1/stream.mjpeg") {
            call.guarded {
                if (core.paused.value) throw LookoutError("paused", 503, "Lookout is paused on the phone")
                val q = call.request.queryParameters
                val fps = (q["fps"]?.toDoubleOrNull() ?: 5.0).coerceIn(0.2, 15.0)
                val width = (q["width"]?.toIntOrNull() ?: 640).coerceIn(64, Compositor.MAX_EDGE)
                val ctx = CallContext("stream", call.request.local.remoteAddress)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=$BOUNDARY")) {
                    core.session.hold {
                        var lastId = -1L
                        var first = true
                        while (!core.paused.value) {
                            val f = withTimeoutOrNull(5_000) { core.store.latest.filterNotNull().first { it.id > lastId } } ?: break
                            lastId = f.id
                            val jpeg = core.ops.encodeJpeg(core.compositor.fit(f.image, width), 70)
                            writeStringUtf8("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                            writeFully(jpeg)
                            writeStringUtf8("\r\n")
                            flush()
                            core.recordServed("stream", ctx, jpeg.size, null, log = first)
                            first = false
                            delay((1000 / fps).toLong())
                        }
                    }
                }
            }
        }

        get("/openapi.json") { call.respondJson(OpenApi.build(routes)) }

        get("/") {
            val token = call.request.queryParameters["token"]
            val access = tokens.access(token, call.request.local.remoteAddress)
            if (access == Access.OWNER || access == Access.CLIENT) {
                call.respondText(WebPage.home(core, token!!), ContentType.Text.Html)
            } else {
                call.respondText(WebPage.login(), ContentType.Text.Html)
            }
        }

        get("/v1/hello") {
            call.respondJson(buildJsonObject {
                put("service", "lookout")
                put("device", core.deviceName)
                put("version", LookoutCore.SERVER_VERSION)
                put("pairing", if (core.settings.value.pairing) "open" else "closed")
                put("server_time", isoTime(core.clock.now()))
            })
        }

        post("/v1/pair") {
            call.guarded {
                val body = call.receiveText().takeIf { it.isNotBlank() }?.let { LookoutJson.parseToJsonElement(it) as? JsonObject }
                val name = (body?.get("name") as? JsonPrimitive)?.content
                call.respondJson(pairing.start(name, call.request.local.remoteAddress, call.application))
            }
        }

        get("/v1/pair/{id}") {
            call.guarded {
                val wait = (call.request.queryParameters["wait_ms"]?.toLongOrNull() ?: 0L).coerceIn(0, 120_000)
                call.respondJson(pairing.result(call.parameters["id"]!!, call.request.local.remoteAddress, wait))
            }
        }

        route("/mcp") {
            handle {
                when (call.request.httpMethod) {
                    HttpMethod.Post -> {
                        val out = mcp.handle(call.receiveText(), call.request.local.remoteAddress)
                        if (out == null) call.respond202() else call.respondText(out.toString(), ContentType.Application.Json)
                    }
                    else -> {
                        call.response.header(HttpHeaders.Allow, "POST")
                        call.respondError(HttpStatusCode.MethodNotAllowed, "method_not_allowed", "This MCP server is stateless: POST JSON-RPC to /mcp")
                    }
                }
            }
        }
    }
}

const val BOUNDARY = "lookoutframe"

/** Paths that need no token. The pairing result under /v1/pair/ is open too. */
private val OPEN_PATHS = setOf("/", "/favicon.ico", "/v1/hello", "/v1/pair")

private fun ApplicationCall.bearer(): String? {
    val h = request.headers[HttpHeaders.Authorization]
    if (h != null && h.startsWith("Bearer ", ignoreCase = true)) return h.substring(7).trim()
    return request.queryParameters["token"]
}

private suspend fun ApplicationCall.respond202() = respondText("", status = HttpStatusCode.Accepted)

private suspend fun ApplicationCall.respondJson(json: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json.toString(), ContentType.Application.Json, status)

suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) =
    respondJson(errorJson(code, message), status)

private suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: LookoutError) {
        respondError(HttpStatusCode.fromValue(e.status), e.code, e.message ?: e.code)
    } catch (e: SerializationException) {
        respondError(HttpStatusCode.BadRequest, "invalid_param", e.message ?: "Invalid request")
    } catch (e: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, "invalid_param", e.message ?: "Invalid request")
    }
}

/** Builds args from the query string, a JSON body, path parameters and the route's fixed values. */
private suspend fun ApplicationCall.restArgs(r: RestRoute): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    request.queryParameters.entries().forEach { (k, v) -> if (k != "token") map[k] = JsonPrimitive(v.joinToString(",")) }
    if (request.httpMethod != HttpMethod.Get && request.httpMethod != HttpMethod.Delete) {
        val text = receiveText()
        if (text.isNotBlank()) {
            val body = LookoutJson.parseToJsonElement(text) as? JsonObject
                ?: throw LookoutError("invalid_body", 400, "The body must be a JSON object")
            map.putAll(body)
        }
    }
    r.pathArgs.forEach { (p, arg) -> parameters[p]?.let { map[arg] = JsonPrimitive(it) } }
    r.fixed.forEach { (k, v) -> map[k] = JsonPrimitive(v) }
    r.defaults.forEach { (k, v) -> map.putIfAbsent(k, JsonPrimitive(v)) }
    return JsonObject(map)
}

private suspend fun ApplicationCall.runCapability(r: RestRoute) = guarded {
    val json = restArgs(r)
    val args = Args(json)
    val result: CapResult = r.capability.call(args, CallContext("rest", request.local.remoteAddress))
    val asJson = args.string("format") == "json"
    val img = result.image
    when {
        r.capability.kind == Capability.Kind.IMAGE && img != null && !asJson -> {
            response.header("X-Lookout-Meta", result.json.toString())
            respondBytes(img.jpeg, ContentType.Image.JPEG)
        }
        r.capability.kind == Capability.Kind.IMAGE && img != null -> respondJson(buildJsonObject {
            put("meta", result.json)
            put("image_b64", Base64.getEncoder().encodeToString(img.jpeg))
        })
        img != null && args.bool("inline") == true ->
            respondJson(JsonObject(result.json + ("image_b64" to JsonPrimitive(Base64.getEncoder().encodeToString(img.jpeg)))))
        else -> respondJson(result.json)
    }
}
