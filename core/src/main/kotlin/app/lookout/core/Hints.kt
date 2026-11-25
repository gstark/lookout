package app.lookout.core

/**
 * Hints that name a better tool, at the moment it helps. They have the lowest priority: a result gives one only when it
 * has no other hint. Each kind goes to a client at most once per [EVERY_MS]. The state is per client and in memory only.
 */
class Hints(private val clock: Clock) {
    enum class Kind { READ, STEP, REPEATED_LOOKS, THIN_TILES }

    /** One look, as the rules see it. [still] is a full-resolution still; [plain] has no settle_ms and no guide. */
    data class Look(val atMs: Long, val roi: String?, val frameId: Long, val still: Boolean, val plain: Boolean)

    private class Client {
        val looks = ArrayDeque<Look>()
        val shown = HashMap<Kind, Long>()
    }

    private val clients = object : LinkedHashMap<String, Client>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Client>) = size > MAX_CLIENTS
    }

    /** Records [look] for [client] and returns its hint. [quiet] means the result already has a hint, so none is given. */
    @Synchronized
    fun look(client: String, look: Look, quiet: Boolean): String? {
        val c = clients.getOrPut(client) { Client() }
        while (c.looks.isNotEmpty() && look.atMs - c.looks.first().atMs > WINDOW_MS) c.looks.removeFirst()
        val earlier = c.looks.toList()
        c.looks.addLast(look)
        if (c.looks.size > MAX_LOOKS) c.looks.removeFirst()
        if (quiet) return null
        return pick(c, Rules.forLook(earlier, look))
    }

    /** The hint for a clip sheet of [tiles] tiles whose roi has [aspect] (long side / short side), or null. */
    @Synchronized
    fun sheet(client: String, tiles: Int, aspect: Double): String? =
        pick(clients.getOrPut(client) { Client() }, listOfNotNull(if (Rules.thinTiles(tiles, aspect)) Kind.THIN_TILES to THIN_TILES else null))

    /** The first candidate whose kind this client has not seen within [EVERY_MS]. */
    private fun pick(c: Client, candidates: List<Pair<Kind, String>>): String? {
        val now = clock.now()
        val (kind, text) = candidates.firstOrNull { (k, _) -> c.shown[k]?.let { now - it < EVERY_MS } != true } ?: return null
        c.shown[kind] = now
        return text
    }

    /** The rules, as pure functions of the looks a client made. */
    object Rules {
        /** Three or more plain looks within [REPEAT_MS]: the agent polls. */
        fun repeatedLooks(earlier: List<Look>, now: Look): Boolean =
            now.plain && earlier.count { it.plain && now.atMs - it.atMs <= REPEAT_MS } >= REPEAT_LOOKS - 1

        /** The last earlier look on the same roi within [WINDOW_MS], when [now] has an roi: the agent steps and looks. */
        fun stepLook(earlier: List<Look>, now: Look): Look? =
            if (now.roi == null) null else earlier.lastOrNull { it.roi == now.roi && now.atMs - it.atMs <= WINDOW_MS }

        /** A second still on the same roi within [WINDOW_MS]: the agent reads text from images. */
        fun readsText(earlier: List<Look>, now: Look): Boolean =
            now.still && earlier.any { it.still && it.roi == now.roi && now.atMs - it.atMs <= WINDOW_MS }

        /** A sheet of many thin tiles: the agent follows a position by eye. */
        fun thinTiles(tiles: Int, aspect: Double): Boolean = tiles >= THIN_MIN_TILES && aspect > THIN_ASPECT

        /** The hints that [now] earns, highest priority first. */
        fun forLook(earlier: List<Look>, now: Look): List<Pair<Kind, String>> = listOfNotNull(
            if (readsText(earlier, now)) Kind.READ to READ else null,
            stepLook(earlier, now)?.let { prev -> Kind.STEP to "To measure this step, call diff a=${prev.frameId} roi=${now.roi} image=false; it gives dx, dy." },
            if (repeatedLooks(earlier, now)) Kind.REPEATED_LOOKS to REPEATED_LOOKS else null,
        )
    }

    companion object {
        /** Each kind of hint goes to a client at most once in this time. */
        const val EVERY_MS = 10 * 60_000L
        const val WINDOW_MS = 60_000L
        const val REPEAT_MS = 15_000L
        const val REPEAT_LOOKS = 3
        const val THIN_MIN_TILES = 6
        const val THIN_ASPECT = 4.0
        const val MAX_LOOKS = 16
        const val MAX_CLIENTS = 32
        const val READ = "To read text or numbers, call read with an roi; it returns them as JSON with no image."
        const val REPEATED_LOOKS = "Instead of repeated looks, call wait_for for a change or stillness, or record then clip for fast motion."
        const val THIN_TILES = "To follow a position, call clip mode=track with this roi; it gives the position on every frame."
    }
}
