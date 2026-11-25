package app.lookout.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What each client last saw, kept across restarts so an update can tell it what changed: the server version of its
 * last describe, and the hash of the tool list it last loaded with tools/list. Keyed by [CallContext.key], at most
 * [MAX_CLIENTS] clients; the one seen least recently goes first.
 */
class ClientState(private val kv: KeyValueStore) {
    @Serializable
    data class Seen(val version: String? = null, val toolsHash: String? = null, val toolsVersion: String? = null)

    private val serializer = MapSerializer(String.serializer(), Seen.serializer())

    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > MAX_CLIENTS
    }.apply {
        kv.get(KEY)?.let { runCatching { LookoutJson.decodeFromString(serializer, it) }.getOrNull() }?.let { putAll(it) }
    }

    @Synchronized
    fun get(client: String): Seen = seen[client] ?: Seen()

    /** Records that [client] saw this server version in describe. */
    fun sawVersion(client: String) = update(client) { it.copy(version = LookoutCore.SERVER_VERSION) }

    /** Records that [client] loaded the tool list with [hash]. */
    fun listedTools(client: String, hash: String) = update(client) { it.copy(toolsHash = hash, toolsVersion = LookoutCore.SERVER_VERSION) }

    /** True when [client] loaded a tool list before and it is not the current one ([hash]). */
    fun toolsChanged(client: String, hash: String): Boolean {
        val s = get(client)
        return s.toolsHash != null && (s.toolsHash != hash || s.toolsVersion != LookoutCore.SERVER_VERSION)
    }

    @Synchronized
    private fun update(client: String, change: (Seen) -> Seen) {
        val old = seen[client] ?: Seen()
        val new = change(old)
        seen[client] = new
        if (new != old) kv.put(KEY, LookoutJson.encodeToString(serializer, seen.toMap()))
    }

    companion object {
        const val KEY = "clients_seen"
        const val MAX_CLIENTS = 32

        /** A short hex SHA-256 of [text]. */
        fun hash(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
