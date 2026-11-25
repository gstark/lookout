package app.lookout.server

import app.lookout.awt.AwtImageOps
import app.lookout.awt.AwtImg
import app.lookout.core.Clock
import app.lookout.core.Colors
import app.lookout.core.DeviceState
import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import app.lookout.core.MemoryBlobStore
import app.lookout.core.MemoryKeyValueStore
import app.lookout.core.ReplayFrameSource
import app.lookout.core.Tools
import app.lookout.core.ClientState
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
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
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
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
    /** Moves the clock forward, for URL expiry. */
    @Volatile private var skewMs = 0L
    private val clock = Clock { System.currentTimeMillis() + skewMs }
    private val kv = MemoryKeyValueStore()
    private val core = LookoutCore(
        AwtImageOps, ReplayFrameSource(frames, fps = 20.0, clock = clock, ops = AwtImageOps), kv, MemoryBlobStore(),
        { DeviceState(batteryPct = 90, charging = true) }, { true }, clock, "test-phone", recordingsDir = recordings,
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
        val videoUrl = json(done.bodyAsText())["recording"]!!.jsonObject["video_url"]!!.jsonPrimitive.content
        assertTrue(videoUrl.startsWith("http://localhost:80/v1/recordings/$id/video?exp="), videoUrl)
        val signed = client.get(videoUrl)
        assertEquals(HttpStatusCode.OK, signed.status, "a signed URL needs no token")
        assertTrue(signed.bodyAsBytes().contentEquals(video.bodyAsBytes()))
        val sheet = client.get("/v1/recordings/$id/clip?tiles=4") { bearerAuth(token) }
        assertEquals(ContentType.Image.JPEG, sheet.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it) })
        val mark = client.post("/v1/recordings/mark") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"recording_id":"$id","marks":[{"label":"a","at":100},{"label":"b","at":"300"}]}""")
        }
        assertEquals(HttpStatusCode.OK, mark.status, mark.bodyAsText())
        assertEquals(listOf("a", "b"), json(mark.bodyAsText())["marks"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content })
        val atMarks = client.get("/v1/recordings/$id/clip?at_marks=true&offset_ms=-50&format=json") { bearerAuth(token) }
        assertEquals(2, json(atMarks.bodyAsText())["meta"]!!.jsonObject["tiles"]!!.jsonArray.size, atMarks.bodyAsText())
        val restAtMs = json(client.get("/v1/recordings/$id/clip?at_ms=100,400&format=json") { bearerAuth(token) }.bodyAsText())["meta"]!!.jsonObject
        assertEquals(listOf("t_ms", "index", "motion"), restAtMs["tile_columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, restAtMs["tiles"]!!.jsonArray.size, "at_ms=100,400 in a query string")
        val mcpClip = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"clip","arguments":{"recording_id":"$id","at_ms":[100,400]}}}""").bodyAsText())["result"]!!.jsonObject
        assertFalse(mcpClip["isError"]!!.jsonPrimitive.boolean, mcpClip.toString())
        val mcpMeta = json(mcpClip["content"]!!.jsonArray[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(2, mcpMeta["tiles"]!!.jsonArray.size, "at_ms as JSON numbers over MCP")
        val trace = json(client.get("/v1/recordings/$id/clip?mode=trace") { bearerAuth(token) }.bodyAsText())
        assertEquals("trace", trace["mode"]!!.jsonPrimitive.content)
        val list = json(client.get("/v1/recordings") { bearerAuth(token) }.bodyAsText())["recordings"]!!.jsonArray
        assertEquals(id, list.single().jsonObject["recording_id"]!!.jsonPrimitive.content)
    }

    @Test fun waitForRecordingDoneClipOverRestAndSchemas() = app {
        val start = client.post("/v1/recordings") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"fps":30,"duration_ms":800}""") }
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val id = json(start.bodyAsText())["recording"]!!.jsonObject["recording_id"]!!.jsonPrimitive.content

        val traceWait = client.post("/v1/wait") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"condition":"recording_done","recording_id":"$id","timeout_ms":20000,"clip":{"mode":"trace"}}""")
        }
        assertEquals(HttpStatusCode.OK, traceWait.status, traceWait.bodyAsText())
        val traceJson = json(traceWait.bodyAsText())
        assertEquals("trace", traceJson["clip"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse("image_id" in traceJson)

        val atWait = client.post("/v1/wait") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"condition":"recording_done","recording_id":"$id","sheet":false,"clip":{"at_ms":[100,400]}}""")
        }
        assertEquals(HttpStatusCode.OK, atWait.status, atWait.bodyAsText())
        val atClip = json(atWait.bodyAsText())["clip"]!!.jsonObject
        assertEquals(2, atClip["tiles"]!!.jsonArray.size)
        val img = client.get(atClip["image_url"]!!.jsonPrimitive.content) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, img.status)
        assertTrue(img.bodyAsBytes().size > 500)

        val noSheet = json(client.post("/v1/wait") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"condition":"recording_done","recording_id":"$id","sheet":false}""")
        }.bodyAsText())
        assertFalse("image_id" in noSheet)
        assertFalse("clip" in noSheet)

        val bad = client.post("/v1/wait") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"condition":"recording_done","recording_id":"$id","clip":{"mode":"nope"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_param", code(bad.bodyAsText()))

        // MCP and OpenAPI clip schemas match clip's own params minus recording_id.
        val tools = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").bodyAsText())["result"]!!.jsonObject["tools"]!!.jsonArray
        val clipToolProps = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "clip" }
            .jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject.keys
        val waitClipProps = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "wait_for" }
            .jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["clip"]!!.jsonObject["properties"]!!.jsonObject.keys
        assertEquals(clipToolProps - "recording_id" - "inline", waitClipProps)

        val openApiWaitClipProps = json(client.get("/openapi.json") { bearerAuth(token) }.bodyAsText())["paths"]!!
            .jsonObject["/v1/wait"]!!.jsonObject["post"]!!.jsonObject["requestBody"]!!.jsonObject["content"]!!
            .jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject["properties"]!!
            .jsonObject["clip"]!!.jsonObject["properties"]!!.jsonObject.keys
        assertEquals(waitClipProps, openApiWaitClipProps)

        val clipAtMs = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "clip" }
            .jsonObject["inputSchema"]!!.jsonObject["properties"]!!.jsonObject["at_ms"]!!.jsonObject
        assertEquals("array", clipAtMs["type"]!!.jsonPrimitive.content)
        assertEquals("integer", clipAtMs["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val openApiAtMs = json(client.get("/openapi.json") { bearerAuth(token) }.bodyAsText())["paths"]!!
            .jsonObject["/v1/recordings/{id}/clip"]!!.jsonObject["get"]!!.jsonObject["parameters"]!!.jsonArray
            .first { it.jsonObject["name"]!!.jsonPrimitive.content == "at_ms" }.jsonObject["schema"]!!.jsonObject
        assertEquals("integer", openApiAtMs["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun describeGivesTheBaseUrl() = app {
        val d = json(client.get("/v1/describe") { bearerAuth(token) }.bodyAsText())
        assertEquals("http://localhost:80", d["base_url"]!!.jsonPrimitive.content)
    }

    @Test fun inlineFalseFrameGivesJsonWithASignedImageUrl() = app {
        val r = client.get("/v1/frame?inline=false&width=320") { bearerAuth(token) }
        assertTrue(r.contentType()?.match(ContentType.Application.Json) == true, "content-type: ${r.contentType()}")
        val url = json(r.bodyAsText())["image_url"]!!.jsonPrimitive.content
        val img = client.get(url)
        assertEquals(HttpStatusCode.OK, img.status, img.bodyAsText())
        assertEquals(ContentType.Image.JPEG, img.contentType()?.withoutParameters())
        assertEquals(320, AwtImageOps.decode(img.bodyAsBytes()).width)
        assertEquals(160, AwtImageOps.decode(client.get("$url&width=160").bodyAsBytes()).width, "width needs no signature")
    }

    @Test fun aSignatureOpensOnlyItsOwnPathUntilItExpires() = app {
        val url = json(client.get("/v1/frame?inline=false") { bearerAuth(token) }.bodyAsText())["image_url"]!!.jsonPrimitive.content
        val query = url.substringAfter('?')
        val sig = query.substringAfter("sig=")
        val tampered = client.get(url.dropLast(2) + if (sig.endsWith("AA")) "BB" else "AA")
        assertEquals(HttpStatusCode.Unauthorized, tampered.status)
        assertEquals("url_invalid", code(tampered.bodyAsText()))
        val otherFrame = client.get("/v1/frames/c999.jpg?$query")
        assertEquals("url_invalid", code(otherFrame.bodyAsText()), "a signature for another path")
        val laterExp = query.replace(Regex("exp=(\\d+)")) { "exp=${it.groupValues[1].toLong() + 3600}" }
        assertEquals("url_invalid", code(client.get("${url.substringBefore('?')}?$laterExp").bodyAsText()), "the expiry is signed")
        for (path in listOf("/v1/describe", "/v1/status", "/v1/frame", "/openapi.json")) {
            val other = client.get("$path?$query")
            assertEquals(HttpStatusCode.Unauthorized, other.status, path)
            assertEquals("unauthorized", code(other.bodyAsText()), path)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp?$query") { setBody("{}") }.status)
        assertEquals(HttpStatusCode.OK, client.get(url).status)
        skewMs = SignedUrls.LIFE_MS + 1_000
        val expired = client.get(url)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals("url_expired", code(expired.bodyAsText()))
        assertTrue("again" in json(expired.bodyAsText())["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    private fun png(width: Int = 400, height: Int = 300): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color(200, 40, 40); fillRect(50, 50, 100, 80); dispose() }
        javax.imageio.ImageIO.write(img, "png", out)
    }.toByteArray()

    @Test fun aScriptUploadsAGuideImageWithTheSignedUrl() = app {
        val uploadUrl = json(client.get("/v1/describe") { bearerAuth(token) }.bodyAsText())["upload_url"]!!.jsonPrimitive.content
        assertTrue(uploadUrl.startsWith("http://localhost:80/v1/uploads?exp="), uploadUrl)
        val r = client.post(uploadUrl) { contentType(ContentType.Image.PNG); setBody(png()) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val id = json(r.bodyAsText())["image_id"]!!.jsonPrimitive.content
        assertEquals(400, AwtImageOps.decode(core.composites.get(id)!!).width)

        val waiting = client.post("/v1/frame") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"guide_image":"$id","wait_ms":0}""") }
        assertEquals(HttpStatusCode.OK, waiting.status, waiting.bodyAsText())
        assertEquals("waiting", json(waiting.bodyAsText())["state"]!!.jsonPrimitive.content)
        val p = withTimeout(5_000) { core.human.current.filterNotNull().first() }
        assertEquals(400, AwtImageOps.decode(p.image!!).width, "the upload shows as the example")
        core.human.answer(p.id, Tools.CANCEL)

        val notImage = client.post(uploadUrl) { setBody("hello".toByteArray()) }
        assertEquals(HttpStatusCode.BadRequest, notImage.status)
        assertEquals("invalid_image", code(notImage.bodyAsText()))
        val big = client.post(uploadUrl) { setBody(ByteArray(MAX_UPLOAD_BYTES + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertEquals("too_large", code(big.bodyAsText()))
        val limited = List(Tools.UPLOAD_BURST) { client.post(uploadUrl) { setBody(png(40, 30)) }.status }
        assertTrue(HttpStatusCode.TooManyRequests in limited, "uploads are rate limited: $limited")
    }

    @Test fun theUploadSignatureOpensOnlyPostToTheUploadPath() = app {
        val uploadUrl = json(client.get("/v1/describe") { bearerAuth(token) }.bodyAsText())["upload_url"]!!.jsonPrimitive.content
        val query = uploadUrl.substringAfter('?')
        val asGet = client.get(uploadUrl)
        assertEquals(HttpStatusCode.Unauthorized, asGet.status, "a POST signature does not allow a GET")
        for (path in listOf("/v1/frame", "/v1/recordings", "/v1/say", "/v1/done", "/mcp", "/v1/uploads/x")) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("$path?$query") { setBody("{}") }.status, path)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/describe?$query").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/uploads") { setBody(png()) }.status, "no signature, no token")

        val imageUrl = json(client.get("/v1/frame?inline=false") { bearerAuth(token) }.bodyAsText())["image_url"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Unauthorized, client.post(imageUrl) { setBody(png()) }.status, "a GET signature does not allow a POST")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/uploads?${imageUrl.substringAfter('?')}") { setBody(png()) }.status)

        val signer = SignedUrls(clock)
        val get = signer.query("GET", "/v1/uploads")
        fun check(method: String, q: String) = signer.check(method, "/v1/uploads", q.substringAfter("exp=").substringBefore('&'), q.substringAfter("sig="))
        assertEquals(SignedUrls.Check.OK, check("GET", get))
        assertEquals(SignedUrls.Check.INVALID, check("POST", get), "the method is signed")
        assertEquals(SignedUrls.Check.INVALID, check("GET", signer.query("POST", "/v1/uploads")))
    }

    @Test fun cropIsARestRouteAndDoesNotWake() = app {
        val id = json(client.get("/v1/frame?inline=false") { bearerAuth(token) }.bodyAsText())["frame_id"]!!.jsonPrimitive.content
        client.post("/v1/done") { bearerAuth(token) }
        assertTrue(core.sleep.asleep.value)
        val r = client.get("/v1/frames/$id/crop?roi=0.25,0.25,0.5,0.5") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(320, AwtImageOps.decode(r.bodyAsBytes()).width)
        assertEquals(id, json(r.headers["X-Lookout-Meta"]!!)["frame_id"]!!.jsonPrimitive.content)
        assertTrue(core.sleep.asleep.value, "crop does not wake Lookout")
    }

    @Test fun flattenOfAKeptFrameIsARestRouteAndDoesNotWake() = app {
        val id = json(client.get("/v1/frame?inline=false") { bearerAuth(token) }.bodyAsText())["frame_id"]!!.jsonPrimitive.content
        client.post("/v1/done") { bearerAuth(token) }
        assertTrue(core.sleep.asleep.value)
        val r = client.post("/v1/flatten") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"frame":"$id","corners":["0.26,0.24","0.76,0.26","0.74,0.76","0.24,0.74"],"width_mm":100,"height_mm":75,"circles":false}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(1024, AwtImageOps.decode(r.bodyAsBytes()).width)
        val meta = json(r.headers["X-Lookout-Meta"]!!)
        assertEquals(id, meta["frame_id"]!!.jsonPrimitive.content)
        assertEquals(4, meta["edges"]!!.jsonObject.size)
        assertTrue(core.sleep.asleep.value, "flatten of a kept frame does not wake Lookout")
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
        val detail = client.get("/v1/frame?detail=max") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, detail.status)
        assertEquals("invalid_param", json(detail.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun postFrameTakesAFullStill() = app {
        val r = client.post("/v1/frame") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"detail":"full"}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("still", json(r.headers["X-Lookout-Meta"]!!)["source"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/capture") { bearerAuth(token) }.status)
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
        for (p in listOf("/v1/frame", "/v1/wait", "/v1/rois/{name}", "/v1/events", "/mcp")) assertTrue(p in paths, p)
        assertEquals(setOf("get", "post"), paths["/v1/frame"]!!.jsonObject.keys)
        assertFalse("/v1/capture" in paths)
    }

    @Test fun webPage() = app {
        val login = client.get("/").bodyAsText()
        assertTrue(login.contains("Enter a token"), login)
        val home = client.get("/?token=$token").bodyAsText()
        assertTrue(home.contains("/v1/stream.mjpeg") && home.contains("test-phone"), home)
        assertFalse(home.contains("Bearer"), "the page gives no setup command")
    }

    private suspend fun ApplicationTestBuilder.rpc(body: String, bearer: String = token) = client.post("/mcp") {
        bearerAuth(bearer)
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        setBody(body)
    }

    @Test fun mcpRefusesAPageFromAnotherSite() = app {
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        suspend fun post(origin: String?) = client.post("/mcp") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            origin?.let { header(HttpHeaders.Origin, it) }
            setBody(init)
        }.status
        assertEquals(HttpStatusCode.OK, post(null))
        assertEquals(HttpStatusCode.Forbidden, post("http://rebind.example.com"))
        assertEquals(HttpStatusCode.Forbidden, post("http://192.168.77.77:8080"))
    }

    @Test fun mcpHandshakeAndTools() = app {
        val init = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""").bodyAsText())
        val res = init["result"]!!.jsonObject
        assertEquals("2025-06-18", res["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(res["instructions"]!!.jsonPrimitive.content.contains("wait_for"))
        assertEquals(HttpStatusCode.Accepted, rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)
        val tools = json(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").bodyAsText())["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("describe", "look", "crop", "flatten", "wait_for", "history", "record", "clip", "diff", "roi", "scale", "baseline", "camera", "say", "ask", "point", "caption", "read", "done"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        tools.forEach { assertEquals("object", it.jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content) }
        val look = json(rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"look","arguments":{"width":256}}}""").bodyAsText())["result"]!!.jsonObject
        val content = look["content"]!!.jsonArray
        assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(256, AwtImageOps.decode(Base64.getDecoder().decode(content[0].jsonObject["data"]!!.jsonPrimitive.content)).width)
        assertTrue(json(content[1].jsonObject["text"]!!.jsonPrimitive.content).containsKey("frame_id"))
        val noImage = json(rpc("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"look","arguments":{"inline":false}}}""").bodyAsText())["result"]!!.jsonObject
        val parts = noImage["content"]!!.jsonArray
        assertEquals(listOf("text"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        val linked = json(parts[0].jsonObject["text"]!!.jsonPrimitive.content)["image_url"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get(linked).status, "the MCP image_url works with no token")
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

    @Test fun darkFrameReturnsJsonOverRestAndMcp() = testApplication {
        val black = AwtImageOps.canvas(640, 480, Colors.BLACK)
        val darkCore = LookoutCore(
            AwtImageOps, ReplayFrameSource(listOf(black), fps = 20.0, ops = AwtImageOps), MemoryKeyValueStore(), MemoryBlobStore(),
            { DeviceState(batteryPct = 90, charging = true) }, { true }, Clock.SYSTEM, "dark-phone",
        ).also { it.start(scope) }
        val darkTokens = TokenStore(MemoryKeyValueStore())
        val darkToken = darkTokens.get()
        application { lookoutModule(darkCore, darkTokens) }

        val rest = client.get("/v1/frame") { bearerAuth(darkToken) }
        assertEquals(HttpStatusCode.OK, rest.status)
        assertTrue(rest.contentType()?.match(ContentType.Application.Json) == true, "content-type: ${rest.contentType()}")
        val body = json(rest.bodyAsText())
        assertTrue(body["dark"]!!.jsonPrimitive.boolean)
        assertTrue(body["brightness"]!!.jsonPrimitive.content.toDouble() < Tools.DARK_FRAME)
        assertTrue("hint" in body && "frame_id" in body)

        val forced = client.get("/v1/frame?force=true") { bearerAuth(darkToken) }
        assertEquals(HttpStatusCode.OK, forced.status)
        assertEquals(ContentType.Image.JPEG, forced.contentType()?.withoutParameters())
        assertTrue(AwtImageOps.decode(forced.bodyAsBytes()).width > 0)
        assertFalse("dark" in json(forced.headers["X-Lookout-Meta"]!!))

        val mcp = json(client.post("/mcp") {
            bearerAuth(darkToken)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"look","arguments":{}}}""")
        }.bodyAsText())["result"]!!.jsonObject
        assertFalse(mcp["isError"]!!.jsonPrimitive.boolean)
        val content = mcp["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(json(content[0].jsonObject["text"]!!.jsonPrimitive.content)["dark"]!!.jsonPrimitive.boolean)
    }

    @Test fun scaleRestMcpAndTicksFrameRoute() = app {
        val empty = json(client.get("/v1/scale") { bearerAuth(token) }.bodyAsText())
        assertEquals("null", empty["scale"]!!.toString())

        val set = client.put("/v1/scale") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"from":"0.2,0.5","to":"0.7,0.5","mm":50}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val setJson = json(set.bodyAsText())
        assertEquals(100.0, setJson["scale"]!!.jsonObject["width_mm"]!!.jsonPrimitive.content.toDouble(), 0.01)
        assertTrue(setJson["valid"]!!.jsonPrimitive.boolean)

        val mcpGet = json(rpc("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"scale","arguments":{"action":"get"}}}""").bodyAsText())["result"]!!.jsonObject
        assertFalse(mcpGet["isError"]!!.jsonPrimitive.boolean)
        val mcpBody = json(mcpGet["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(100.0, mcpBody["scale"]!!.jsonObject["width_mm"]!!.jsonPrimitive.content.toDouble(), 0.01)

        val look = json(client.get("/v1/frame?inline=false&width=320&grid=ticks") { bearerAuth(token) }.bodyAsText())
        assertEquals(100.0 / 320.0, look["mm_per_px"]!!.jsonPrimitive.content.toDouble(), 0.002)
        val fid = look["frame_id"]!!.jsonPrimitive.content
        val raw = client.get("/v1/frames/$fid.jpg?width=320") { bearerAuth(token) }.bodyAsBytes()
        val ticked = client.get("/v1/frames/$fid.jpg?width=320&grid=ticks") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, ticked.status)
        assertEquals(320, AwtImageOps.decode(ticked.bodyAsBytes()).width)
        assertFalse(raw.contentEquals(ticked.bodyAsBytes()), "grid=ticks should draw ticks on /v1/frames/{id}.jpg")

        val cleared = json(client.delete("/v1/scale") { bearerAuth(token) }.bodyAsText())
        assertTrue(cleared["cleared"]!!.jsonPrimitive.boolean)
        val afterClear = json(client.get("/v1/scale") { bearerAuth(token) }.bodyAsText())
        assertEquals("null", afterClear["scale"]!!.toString())
    }

    private fun describeOverMcp(text: String) = json(json(text)["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

    private suspend fun ApplicationTestBuilder.describeMcp(bearer: String = token) =
        describeOverMcp(rpc("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"describe","arguments":{}}}""", bearer).bodyAsText())

    /** Opens the MCP event stream and returns its data lines up to the first keep-alive comment. */
    private suspend fun ApplicationTestBuilder.streamStart(bearer: String = token): List<String> =
        client.prepareGet("/mcp") { bearerAuth(bearer); header(HttpHeaders.Accept, "text/event-stream") }.execute { r ->
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(ContentType.Text.EventStream, r.headers[HttpHeaders.ContentType]?.let { ContentType.parse(it).withoutParameters() })
            val ch = r.bodyAsChannel()
            val data = mutableListOf<String>()
            withTimeout(5_000) {
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    if (line.startsWith(":")) break
                    if (line.startsWith("data: ")) data += line.removePrefix("data: ")
                }
            }
            data
        }

    @Test fun mcpSaysTheToolListChangedAfterAnUpdate() = app {
        val init = json(rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""").bodyAsText())
        assertTrue(init["result"]!!.jsonObject["capabilities"]!!.jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp") { bearerAuth(token); header(HttpHeaders.Accept, "application/json") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp") { header(HttpHeaders.Accept, "text/event-stream") }.status)

        assertEquals(emptyList(), streamStart(), "a client that never listed tools gets no notification")
        assertFalse("tools_changed" in describeMcp())

        // The tool list this client loaded before an update.
        core.clients.listedTools("mcp ${TokenStore.OWNER}", "0123456789abcdef")
        val sent = streamStart()
        assertEquals(1, sent.size)
        assertEquals("notifications/tools/list_changed", json(sent.single())["method"]!!.jsonPrimitive.content)
        assertFalse("id" in json(sent.single()), "a notification has no id")
        assertTrue(describeMcp()["tools_changed"]!!.jsonPrimitive.boolean)

        rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(emptyList(), streamStart())
        assertFalse("tools_changed" in describeMcp())
    }

    @Test fun anMcpStreamDoesNotWakeLookoutOrOpenTheCamera() = app {
        assertTrue(json(client.post("/v1/done") { bearerAuth(token) }.bodyAsText())["asleep"]!!.jsonPrimitive.boolean)
        withTimeout(5_000) { core.session.open.first { !it } }
        streamStart()
        assertTrue(core.sleep.asleep.value, "the stream did not wake Lookout")
        assertFalse(core.session.open.value, "the stream did not open the camera")
        assertTrue(core.sleepBlockers().isEmpty(), "the stream does not keep Lookout awake")
    }

    @Test fun mcpStreamsAreLimited() = app {
        val mcp = McpHandler(core)
        val first = mcp.openStream("a")!!
        repeat(McpHandler.MAX_STREAMS_PER_CLIENT - 1) { mcp.openStream("a")!! }
        assertFalse(first.stop.isCompleted)
        val newest = mcp.openStream("a")
        assertTrue(newest != null && first.stop.isCompleted, "a new stream of a client replaces its oldest")
        repeat(McpHandler.MAX_STREAMS - McpHandler.MAX_STREAMS_PER_CLIENT) { assertTrue(mcp.openStream("c$it") != null) }
        assertNull(mcp.openStream("z"), "in all")
        mcp.closeStream(newest!!)
        assertTrue(mcp.openStream("z") != null)
    }

    /** Opens an event stream with [bearer] and keeps it open. Returns the job and a signal: ready, then ended. */
    private fun ApplicationTestBuilder.hold(scope: CoroutineScope, bearer: String): Triple<kotlinx.coroutines.Job, kotlinx.coroutines.CompletableDeferred<Unit>, kotlinx.coroutines.CompletableDeferred<Unit>> {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = scope.launch {
            client.prepareGet("/mcp") { bearerAuth(bearer); header(HttpHeaders.Accept, "text/event-stream") }.execute { r ->
                val ch = r.bodyAsChannel()
                while (true) {
                    val line = ch.readUTF8Line() ?: break
                    if (line.startsWith(":")) ready.complete(Unit)
                }
                ended.complete(Unit)
            }
        }
        return Triple(job, ready, ended)
    }

    @Test fun mcpStreamsOverHttpReplaceTheOldestAndRefuseBeyondTheTotal() = app {
        kotlinx.coroutines.coroutineScope {
            val owner = List(McpHandler.MAX_STREAMS_PER_CLIENT) { hold(this, token) }
            withTimeout(5_000) { owner.forEach { it.second.await() } }
            val third = hold(this, token)
            withTimeout(5_000) {
                third.second.await()
                while (owner.none { it.third.isCompleted }) kotlinx.coroutines.delay(20)
            }
            assertEquals(1, owner.count { it.third.isCompleted }, "the third stream of a client ends one older stream")
            val agents = List((McpHandler.MAX_STREAMS - McpHandler.MAX_STREAMS_PER_CLIENT) / McpHandler.MAX_STREAMS_PER_CLIENT) { tokens.addClient("agent $it", null, 0) }
            val more = agents.flatMap { t -> List(McpHandler.MAX_STREAMS_PER_CLIENT) { hold(this, t) } }
            withTimeout(5_000) { more.forEach { it.second.await() } }
            val refused = client.get("/mcp") { bearerAuth(tokens.addClient("late", null, 0)); header(HttpHeaders.Accept, "text/event-stream") }
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals("too_many_streams", json(refused.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            (owner + third + more).forEach { it.first.cancel() }
        }
    }

    @Test fun pairedClientsKeepTheirOwnStateAndNoTokenIsStored() = app {
        val a = tokens.addClient("agent a", null, 0)
        val b = tokens.addClient("agent b", null, 0)
        assertTrue("changes" in describeMcp(a))
        assertFalse("changes" in describeMcp(a), "a saw the changes")
        assertTrue("changes" in describeMcp(b), "b has its own record")
        rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", a)
        val stored = kv.get(ClientState.KEY)!!
        val ids = tokens.clients.value.map { it.id }
        ids.forEach { assertTrue("client:$it" in stored, stored) }
        for (secret in listOf(token, a, b) + tokens.clients.value.map { it.tokenHash }) assertFalse(secret in stored, "no token text in the stored record")
    }
}
