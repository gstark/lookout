package app.lookout.server

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import app.lookout.core.Clock
import app.lookout.core.DeviceState
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import app.lookout.core.MemoryBlobStore
import app.lookout.core.MemoryKeyValueStore
import app.lookout.core.ReplayFrameSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = List(4) { i ->
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(100, 100, 100); g.fillRect(0, 0, 640, 480)
        g.color = Color(30, 120, 60); g.fillRect(160 + i, 120, 320, 240)
        g.dispose()
        AwtImg(img)
    }
    private val recordings = java.nio.file.Files.createTempDirectory("lookout-server-rec").toFile()
    private val core = LookoutCore(
        AwtImageOps, ReplayFrameSource(frames, fps = 20.0, ops = AwtImageOps), MemoryKeyValueStore(), MemoryBlobStore(),
        { DeviceState(batteryPct = 90, charging = true) }, { true }, Clock.SYSTEM, "test-phone", recordingsDir = recordings,
    ).also { it.start(scope) }
    private val tokens = TokenStore(MemoryKeyValueStore())
    private val token = tokens.get()

    @AfterTest fun tearDown() {
        scope.cancel()
        recordings.deleteRecursively()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { lookoutModule(core, tokens) }
        block()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun recordOverRestAndDownloadTheClip() = app {
        val start = client.post("/v1/recordings") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"fps":30,"duration_ms":800}""") }
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val id = json(start.bodyAsText())["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content
        val busy = client.post("/v1/recordings") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, busy.status)
        val done = client.post("/v1/wait") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"condition":"recording_done","recording_id":"$id","timeout_ms":20000}""") }
        assertEquals("ready", json(done.bodyAsText())["recording"]!!.jsonObject["state"]!!.jsonPrimitive.content, done.bodyAsText())
        val video = client.get("/v1/recordings/$id/video") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, video.status)
        assertTrue(video.bodyAsBytes().size > 1000)
        val sheet = client.get("/v1/recordings/$id/clip?tiles=4") { bearerAuth(token) }
        assertEquals(ContentType.Image.JPEG, sheet.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) })
        val trace = json(client.get("/v1/recordings/$id/clip?mode=trace") { bearerAuth(token) }.bodyAsText())
        assertEquals("trace", trace["mode"]!!.jsonPrimitive.content)
        val list = json(client.get("/v1/recordings") { bearerAuth(token) }.bodyAsText())["recordings"]!!.jsonArray
        assertEquals(id, list.single().jsonObject["recording_id"]!!.jsonPrimitive.content)
    }

    @Test fun lanFilter() {
        for (ip in listOf("192.168.1.9", "10.0.0.2", "172.16.5.4", "169.254.1.1", "127.0.0.1", "::1", "fe80::1", "fd12::3", "::ffff:192.168.0.4")) assertTrue(Lan.isPrivate(ip), ip)
        for (ip in listOf("8.8.8.8", "172.32.0.1", "2001:4860::8888", "example.com", "")) assertFalse(Lan.isPrivate(ip), ip)
    }

    @Test fun tailnetFilter() {
        val tailnet = listOf("100.64.0.1", "100.101.5.9", "100.127.255.254", "fd7a:115c:a1e0::1", "::ffff:100.64.0.1", "/100.64.0.1")
        for (ip in tailnet) {
            assertTrue(Lan.isTailnet(ip), ip)
            assertFalse(Lan.isPrivate(ip), ip)
            assertTrue(Lan.allowsRemote(ip, tailscale = true), ip)
            assertFalse(Lan.allowsRemote(ip, tailscale = false), ip)
        }
        for (ip in listOf("100.63.255.255", "100.128.0.1", "fd7a:115c:a1e1::1", "192.168.1.9", "localhost", "")) assertFalse(Lan.isTailnet(ip), ip)
        for (ip in listOf("192.168.1.9", "127.0.0.1", "fd12::3")) assertTrue(Lan.allowsRemote(ip, tailscale = false), ip)
        for (ip in listOf("8.8.8.8", "100.128.0.1")) assertFalse(Lan.allowsRemote(ip, tailscale = true), ip)
    }

    private fun code(text: String) = json(text)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    /** Taps a button on the pairing card, as the person at the phone does. */
    private suspend fun tap(choice: String): String {
        val p = withTimeout(5_000) { core.human.current.filterNotNull().first() }
        core.human.answer(p.id, choice)
        return p.text
    }

    private suspend fun ApplicationTestBuilder.requestPair(name: String = "exe-agent-3") =
        client.post("/v1/pair") { contentType(ContentType.Application.Json); setBody("""{"name":"$name"}""") }

    @Test fun helloNeedsNoToken() = app {
        val hello = json(client.get("/v1/hello").bodyAsText())
        assertEquals("lookout", hello["service"]!!.jsonPrimitive.content)
        assertEquals("test-phone", hello["device"]!!.jsonPrimitive.content)
        assertEquals("open", hello["pairing"]!!.jsonPrimitive.content)
        java.time.Instant.parse(hello["server_time"]!!.jsonPrimitive.content)
    }

    @Test fun pairingGivesTheAgentItsOwnToken() = app {
        val asked = json(requestPair().bodyAsText())
        val id = asked["pair_id"]!!.jsonPrimitive.content
        val pairCode = asked["code"]!!.jsonPrimitive.content
        assertTrue(Regex("\\d{4}").matches(pairCode), pairCode)
        assertEquals("pending", json(client.get("/v1/pair/$id").bodyAsText())["state"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.Conflict, requestPair("second").status)
        val card = tap(Pairing.ALLOW)
        assertTrue("exe-agent-3" in card && "Code $pairCode" in card, card)

        val approved = json(client.get("/v1/pair/$id?wait_ms=5000").bodyAsText())
        assertEquals("approved", approved["state"]!!.jsonPrimitive.content)
        val agentToken = approved["token"]!!.jsonPrimitive.content
        assertTrue(agentToken != token && "bound_to" !in approved)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/pair/$id").status, "the token is given one time")

        assertEquals(HttpStatusCode.OK, client.get("/v1/status") { bearerAuth(agentToken) }.status)
        assertTrue("stream.mjpeg" in client.get("/?token=$agentToken").bodyAsText(), "a paired token opens the web page")

        val paired = tokens.clients.value.single()
        assertEquals("exe-agent-3", paired.name)
        tokens.revoke(paired.id)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/status") { bearerAuth(agentToken) }.status)
    }

    @Test fun threeDenialsBlockTheAddress() = app {
        repeat(Pairing.MAX_DENIALS) {
            val id = json(requestPair().bodyAsText())["pair_id"]!!.jsonPrimitive.content
            tap(Pairing.DENY)
            val denied = client.get("/v1/pair/$id?wait_ms=5000")
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertEquals("pair_denied", code(denied.bodyAsText()))
        }
        val blocked = requestPair()
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("pair_blocked", code(blocked.bodyAsText()))
        assertTrue(tokens.clients.value.isEmpty())
    }

    @Test fun pairingCanBeClosed() = app {
        core.updateSettings { it.copy(pairing = false) }
        assertEquals("closed", json(client.get("/v1/hello").bodyAsText())["pairing"]!!.jsonPrimitive.content)
        assertEquals("pairing_closed", code(requestPair().bodyAsText()))
        core.updateSettings { it.copy(pairing = true) }
        // A name with no letter or digit is empty after the filter.
        assertEquals("invalid_param", code(requestPair("\\n").bodyAsText()))
    }

    @Test fun pairingBindsATailnetAgentToItsAddress() = runBlocking {
        val pairing = Pairing(core, tokens)
        val id = pairing.start("exe-agent-3", "100.101.5.9", scope)["pair_id"]!!.jsonPrimitive.content
        assertEquals("pair_not_found", assertFailsWith<LookoutError> { pairing.result(id, "100.101.5.10", 0) }.code)
        assertTrue("100.101.5.9" in tap(Pairing.ALLOW))
        val approved = pairing.result(id, "100.101.5.9", 5_000)
        assertEquals("100.101.5.9", approved["bound_to"]!!.jsonPrimitive.content)
        val agentToken = approved["token"]!!.jsonPrimitive.content
        assertEquals(Access.CLIENT, tokens.access(agentToken, "100.101.5.9"))
        assertEquals(Access.CLIENT, tokens.access(agentToken, "::ffff:100.101.5.9"))
        assertEquals(Access.WRONG_ADDRESS, tokens.access(agentToken, "100.101.5.10"))
        assertEquals(Access.WRONG_ADDRESS, tokens.access(agentToken, "192.168.1.9"))
        assertEquals(Access.DENIED, tokens.access("nope", "100.101.5.9"))
        assertEquals(Access.OWNER, tokens.access(token, "100.101.5.10"))
    }

    @Test fun pairingExpiresWithoutAnAnswer() = runBlocking {
        val pairing = Pairing(core, tokens, timeoutMs = 200)
        val id = pairing.start("exe-agent-3", "192.168.1.9", scope)["pair_id"]!!.jsonPrimitive.content
        assertEquals("pair_expired", assertFailsWith<LookoutError> { pairing.result(id, "192.168.1.9", 5_000) }.code)
        assertTrue(tokens.clients.value.isEmpty())
    }

    @Test fun tokenIsRequired() = app {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/status").status)
        val wrong = client.get("/v1/status") { bearerAuth("nope") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("unauthorized", json(wrong.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/v1/status") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/status?token=$token").status)
        assertTrue(tokens.matches(token) && !tokens.matches(token + "x") && token.length >= 43)
    }

    @Test fun doneSleepsAndTheNextCallWakes() = app {
        val done = json(client.post("/v1/done") { bearerAuth(token) }.bodyAsText())
        assertEquals(true, done["asleep"]!!.jsonPrimitive.boolean)
        val status = json(client.get("/v1/status") { bearerAuth(token) }.bodyAsText())
        assertEquals(true, status["asleep"]!!.jsonPrimitive.boolean, "status does not wake it")
        assertEquals(HttpStatusCode.OK, client.get("/v1/frame?fresh=true") { bearerAuth(token) }.status)
        assertEquals(false, core.sleep.asleep.value, "a look wakes it")
        assertEquals(listOf("asleep", "awake"), core.events.since(0).filter { it.type == "sleep" }.map { it.data["state"]!!.jsonPrimitive.content })
    }

    @Test fun frameReturnsJpegWithMetaHeader() = app {
        val r = client.get("/v1/frame?width=320&stamp=true") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Image.JPEG, r.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) })
        val meta = json(r.headers["X-Lookout-Meta"]!!)
        assertEquals(320, meta["width"]!!.jsonPrimitive.content.toInt())
        assertEquals(320, AwtImageOps.decode(r.bodyAsBytes()).width)
        val j = json(client.get("/v1/frame?format=json&fresh=true") { bearerAuth(token) }.bodyAsText())
        assertTrue(Base64.getDecoder().decode(j["image_b64"]!!.jsonPrimitive.content).size > 1000)
        assertTrue("frame_id" in j["meta"]!!.jsonObject)
    }

    @Test fun badParamsGiveErrorShape() = app {
        val r = client.get("/v1/frame?colour=red") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("unknown_param", json(r.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val roi = client.get("/v1/frame?roi=missing") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, roi.status)
    }

    @Test fun roiCrud() = app {
        val put = client.put("/v1/rois/desk") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"from":"A1","to":"B2"}""") }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val list = json(client.get("/v1/rois") { bearerAuth(token) }.bodyAsText())["rois"]!!.jsonArray
        assertEquals("desk", list.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, client.get("/v1/frame?roi=desk") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.delete("/v1/rois/desk") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/rois/desk") { bearerAuth(token) }.status)
    }

    @Test fun waitTimesOutWithImageUrl() = app {
        val r = client.post("/v1/wait") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"condition":"change","roi":"0.0,0.0,0.2,0.2","timeout_ms":300}""") }
        val j = json(r.bodyAsText())
        assertFalse(j["matched"]!!.jsonPrimitive.boolean)
        val img = client.get(j["image_url"]!!.jsonPrimitive.content) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, img.status)
        assertTrue(img.bodyAsBytes().size > 500)
    }

    @Test fun baselineAndDiffOverRest() = app {
        assertEquals(HttpStatusCode.OK, client.post("/v1/baselines/ref") { bearerAuth(token) }.status)
        val d = json(client.post("/v1/diff") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"a":"ref"}""") }.bodyAsText())
        assertTrue("score" in d && "regions" in d)
        assertEquals(HttpStatusCode.OK, client.get("/v1/frames/ref.jpg?width=200") { bearerAuth(token) }.status)
    }

    @Test fun eventsAndOpenApi() = app {
        client.get("/v1/frame") { bearerAuth(token) }
        val e = json(client.get("/v1/events?since_id=0&types=request") { bearerAuth(token) }.bodyAsText())
        assertTrue(e["events"]!!.jsonArray.isNotEmpty())
        val o = json(client.get("/openapi.json") { bearerAuth(token) }.bodyAsText())
        val paths = o["paths"]!!.jsonObject
        for (p in listOf("/v1/frame", "/v1/capture", "/v1/wait", "/v1/rois/{name}", "/v1/events", "/mcp")) assertTrue(p in paths, p)
    }

    @Test fun webPage() = app {
        val login = client.get("/").bodyAsText()
        assertTrue(login.contains("Enter a token"), login)
        val home = client.get("/?token=$token").bodyAsText()
        assertTrue(home.contains("/v1/stream.mjpeg") && home.contains("test-phone"), home)
        assertFalse(home.contains("Bearer"), "the page gives no setup command")
    }

    private suspend fun ApplicationTestBuilder.rpc(body: String) = client.post("/mcp") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(body)
    }

    @Test fun mcpHandshakeAndTools() = app {
        val init = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""").bodyAsText())
        val res = init["result"]!!.jsonObject
        assertEquals("2025-06-18", res["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(res["instructions"]!!.jsonPrimitive.content.contains("wait_for"))
        assertEquals(HttpStatusCode.Accepted, rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        val tools = json(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText())["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("describe", "look", "capture", "wait_for", "history", "record", "clip", "diff", "roi", "baseline", "camera", "say", "ask", "point", "caption", "read", "done"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        tools.forEach { assertEquals("object", it.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content) }
        val look = json(rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"look","arguments":{"width":256}}}""").bodyAsText())["result"]!!.jsonObject
        val content = look["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(256, AwtImageOps.decode(Base64.getDecoder().decode(content[0].jsonObject["data"]!!.jsonPrimitive.content)).width)
        assertTrue(json(content[1].jsonObject["text"]!!.jsonPrimitive.content).containsKey("frame_id"))
        val bad = json(rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"look","arguments":{"roi":"ghost"}}}""").bodyAsText())["result"]!!.jsonObject
        assertTrue(bad["isError"]!!.jsonPrimitive.boolean)
        val unknown = json(rpc("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""").bodyAsText())
        assertEquals(-32602, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val parse = json(rpc("{not json").bodyAsText())
        assertEquals(-32700, parse["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { setBody("{}") }.status)
    }

    @Test fun mcpWaitForReturnsImageAndJson() = app {
        val r = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"wait_for","arguments":{"condition":"motion","timeout_ms":300}}}""").bodyAsText())
        val content = r["result"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        val meta: JsonObject = json(content[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue("matched" in meta)
    }
}
