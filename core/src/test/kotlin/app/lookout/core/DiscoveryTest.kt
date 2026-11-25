package app.lookout.core

import app.lookout.awt.AwtImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** describe lists the tools and the changes, and results point at a better tool. */
class DiscoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000L
    private val clock = Clock { now }

    @AfterTest fun tearDown() = scope.cancel()

    private fun args(vararg pairs: Pair<String, Any>) = Args(buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Number) put(k, v) else if (v is Boolean) put(k, v) else put(k, v.toString()) }
    })

    private fun describe(core: LookoutCore, ctx: CallContext, vararg pairs: Pair<String, Any>): JsonObject =
        runBlocking { core.capability("describe").call(args(*pairs), ctx).json }

    private fun versions(d: JsonObject) = d["changes"]?.jsonArray?.map { it.jsonObject["version"]!!.jsonPrimitive.content }

    @Test fun describeListsEveryMcpToolWithAShortSummary() {
        val core = Fixtures.core(PushSource())
        val tools = describe(core, CallContext("mcp", client = "owner"))["tools"]!!.jsonObject
        assertEquals(core.tools.mcpTools.map { it.name }, tools.keys.toList())
        tools.forEach { (name, summary) ->
            val text = summary.jsonPrimitive.content
            assertTrue(text.isNotBlank() && text.length <= Capability.SUMMARY_MAX, "$name: '$text'")
        }
        assertTrue(tools.toString().length < 1_250, "the list stays near 300 tokens: ${tools.toString().length} chars")
        assertFailsWith<IllegalArgumentException> { Capability("x", "", "A tool.", emptyList(), Capability.Kind.JSON) { _, _ -> CapResult(JsonObject(emptyMap())) } }
    }

    @Test fun theChangelogStartsAtTheServerVersionAndKeepsLinesShort() {
        assertEquals(LookoutCore.SERVER_VERSION, Changelog.entries.first().version)
        assertEquals(Changelog.entries.sortedWith { a, b -> Changelog.compare(b.version, a.version) }, Changelog.entries)
        Changelog.entries.forEach { e ->
            assertTrue(e.lines.size <= 25, e.version)
            e.lines.forEach { assertTrue(it.length < 100, it) }
        }
    }

    @Test fun changesShowOnceForEachClientAndAgainAfterAnUpdate() {
        val kv = MemoryKeyValueStore()
        val core = LookoutCore(AwtImageOps, PushSource(), kv, MemoryBlobStore(), { DeviceState() }, { true }, clock)
        val a = CallContext("mcp", "10.0.0.2", client = "client:a")
        assertEquals(listOf(LookoutCore.SERVER_VERSION), versions(describe(core, a)), "a client never seen gets the newest entry")
        assertNull(versions(describe(core, CallContext("mcp", "10.0.0.3", client = "client:a"))), "the same client from a new address saw it already")
        assertEquals(listOf(LookoutCore.SERVER_VERSION), versions(describe(core, CallContext("mcp", "10.0.0.2", client = "client:b"))), "another client has its own record")
        assertEquals(Changelog.entries.map { it.version }, versions(describe(core, a, "changes" to "all")))
        assertEquals(listOf(LookoutCore.SERVER_VERSION), versions(describe(core, a, "changes" to "0.1.0")))
        assertEquals("invalid_param", assertFailsWith<LookoutError> { describe(core, a, "changes" to "lately") }.code)

        // The record is persistent. A client that last saw an older version gets the newer entries once.
        kv.put(ClientState.KEY, kv.get(ClientState.KEY)!!.replace("\"${LookoutCore.SERVER_VERSION}\"", "\"0.1.0\""))
        val updated = LookoutCore(AwtImageOps, PushSource(), kv, MemoryBlobStore(), { DeviceState() }, { true }, clock)
        assertEquals(listOf(LookoutCore.SERVER_VERSION), versions(describe(updated, a)))
        assertNull(versions(describe(updated, a)))
    }

    @Test fun describeSaysWhenTheToolListOfAClientIsOld() {
        val core = Fixtures.core(PushSource())
        val ctx = CallContext("mcp", client = "owner")
        assertFalse("tools_changed" in describe(core, ctx), "a client that never listed tools")
        core.clients.listedTools(ctx.key, "0123456789abcdef")
        assertTrue("tools_note" in describe(core, ctx))
        assertFalse("tools_changed" in describe(core, CallContext("rest", client = "owner")), "REST clients are not affected")
        core.clients.listedTools(ctx.key, core.tools.toolsHash)
        assertFalse("tools_changed" in describe(core, ctx))
    }

    // ---- hints ----

    private fun look(at: Long, roi: String? = null, id: Long = at, still: Boolean = false, plain: Boolean = true) = Hints.Look(at, roi, id, still, plain)

    @Test fun repeatedLooksRule() {
        val two = listOf(look(0), look(5_000))
        assertTrue(Hints.Rules.repeatedLooks(two, look(10_000)))
        assertFalse(Hints.Rules.repeatedLooks(two.take(1), look(10_000)), "two looks are not a loop")
        assertFalse(Hints.Rules.repeatedLooks(two, look(20_000)), "the first look is older than 15 s")
        assertFalse(Hints.Rules.repeatedLooks(two, look(10_000, plain = false)), "a look with settle_ms or a guide")
        assertFalse(Hints.Rules.repeatedLooks(listOf(look(0, plain = false), look(5_000)), look(10_000)))
    }

    @Test fun stepLookRule() {
        val earlier = listOf(look(0, "arm", id = 7), look(1_000, "desk", id = 8))
        assertEquals(7L, Hints.Rules.stepLook(earlier, look(30_000, "arm"))?.frameId)
        assertNull(Hints.Rules.stepLook(earlier, look(61_000, "arm")), "older than 60 s")
        assertNull(Hints.Rules.stepLook(earlier, look(30_000, "lid")))
        assertNull(Hints.Rules.stepLook(listOf(look(0)), look(1_000)), "no roi")
    }

    @Test fun readsTextRule() {
        assertTrue(Hints.Rules.readsText(listOf(look(0, "lcd", still = true)), look(30_000, "lcd", still = true)))
        assertFalse(Hints.Rules.readsText(listOf(look(0, "lcd", still = true)), look(30_000, "lcd")), "a preview frame")
        assertFalse(Hints.Rules.readsText(listOf(look(0, "lcd", still = true)), look(61_000, "lcd", still = true)))
        assertFalse(Hints.Rules.readsText(listOf(look(0, "lcd", still = true)), look(1_000, "pcb", still = true)))
    }

    @Test fun thinTilesRule() {
        assertTrue(Hints.Rules.thinTiles(6, 4.1))
        assertFalse(Hints.Rules.thinTiles(5, 10.0))
        assertFalse(Hints.Rules.thinTiles(12, 4.0))
    }

    @Test fun aHintKindGoesToAClientOncePerWindow() {
        val hints = Hints(clock)
        assertEquals(Hints.THIN_TILES, hints.sheet("a", 8, 10.0))
        assertNull(hints.sheet("a", 8, 10.0))
        assertEquals(Hints.THIN_TILES, hints.sheet("b", 8, 10.0), "each client has its own window")
        now += Hints.EVERY_MS - 1
        assertNull(hints.sheet("a", 8, 10.0))
        now += 1
        assertEquals(Hints.THIN_TILES, hints.sheet("a", 8, 10.0))
    }

    /** A warm core with a still scene, so no other hint shows. */
    private suspend fun warmCore(): Pair<LookoutCore, PushSource> {
        val s = PushSource(still = Fixtures.scene(), clock = clock)
        val c = Fixtures.core(s, clock = clock).also { it.start(scope) }
        c.session.open.first { it }
        s.push(Fixtures.scene())
        return c to s
    }

    private suspend fun hintOf(c: LookoutCore, ctx: CallContext, vararg pairs: Pair<String, Any>): String? =
        c.capability("look").call(args("fresh" to false, *pairs), ctx).json["hint"]?.jsonPrimitive?.content

    @Test fun repeatedLooksPointAtWaitForOncePerWindow() = runBlocking {
        val (c, s) = warmCore()
        val ctx = CallContext("mcp", client = "owner")
        assertNull(hintOf(c, ctx))
        now += 4_000; s.push(Fixtures.scene())
        assertNull(hintOf(c, ctx))
        now += 4_000; s.push(Fixtures.scene())
        assertEquals(Hints.REPEATED_LOOKS, hintOf(c, ctx))
        now += 4_000; s.push(Fixtures.scene())
        assertNull(hintOf(c, ctx), "not again within the window")
        assertNull(hintOf(c, CallContext("mcp", client = "client:x")), "another client made one look")
        now += Hints.EVERY_MS; s.push(Fixtures.scene())
        repeat(2) { assertNull(hintOf(c, ctx)); now += 1_000; s.push(Fixtures.scene()) }
        assertEquals(Hints.REPEATED_LOOKS, hintOf(c, ctx))
        assertNull(hintOf(c, ctx, "settle_ms" to 0), "a look with settle_ms is not a loop")
    }

    @Test fun aHigherHintWinsAndTheBetterToolHintWaits() = runBlocking {
        val (c, s) = warmCore()
        val ctx = CallContext("mcp", client = "owner")
        repeat(2) { hintOf(c, ctx); now += 1_000; s.push(Fixtures.scene()) }
        s.push(Fixtures.scene(handX = 0.4))
        assertEquals(Tools.MOVING_HINT, hintOf(c, ctx), "the moving hint wins")
        now += Tools.MOTION_WINDOW_MS + 100; s.push(Fixtures.scene(handX = 0.4))
        assertEquals(Hints.REPEATED_LOOKS, hintOf(c, ctx), "it was not used up")
    }

    @Test fun stepAndLookPointsAtDiff() = runBlocking {
        val (c, s) = warmCore()
        val ctx = CallContext("mcp", client = "owner")
        val first = c.capability("look").call(args("fresh" to false, "roi" to "0.1,0.1,0.5,0.5", "width" to 256), ctx).json
        assertNull(first["hint"])
        now += 20_000; s.push(Fixtures.scene())
        val id = first["frame_id"]!!.jsonPrimitive.content
        assertEquals("To measure this step, call diff a=$id roi=0.1,0.1,0.5,0.5 image=false; it gives dx, dy.", hintOf(c, ctx, "roi" to "0.1,0.1,0.5,0.5", "width" to 256))
        now += 20_000; s.push(Fixtures.scene())
        assertNull(hintOf(c, ctx, "roi" to "0.6,0.6,0.3,0.3", "width" to 256), "another roi")
    }

    @Test fun twoStillsOfOneRoiPointAtRead() = runBlocking {
        val (c, s) = warmCore()
        val ctx = CallContext("mcp", client = "owner")
        assertNull(hintOf(c, ctx, "detail" to "full", "roi" to "0.2,0.2,0.3,0.2"))
        now += 30_000; s.push(Fixtures.scene())
        assertEquals(Hints.READ, hintOf(c, ctx, "detail" to "full", "roi" to "0.2,0.2,0.3,0.2"))
    }
}
