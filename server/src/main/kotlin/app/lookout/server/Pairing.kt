package app.lookout.server

import app.lookout.core.LookoutCore
import app.lookout.core.LookoutError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets an agent ask for its own token. The phone shows the agent name, its address and a code.
 * The agent shows the same code, so the person can tell which request they approve.
 */
class Pairing(private val core: LookoutCore, private val tokens: TokenStore, private val timeoutMs: Long = 120_000) {
    private enum class Outcome { ALLOWED, DENIED, EXPIRED }

    private class Request(val id: String, val code: String, val name: String, val remote: String, val createdAt: Long) {
        val outcome = CompletableDeferred<Outcome>()
    }

    private val requests = ConcurrentHashMap<String, Request>()
    private val denials = HashMap<String, List<Long>>()

    /** Shows a request on the phone and returns its id and code. [scope] must outlive the HTTP call. */
    @Synchronized
    fun start(rawName: String?, remote: String, scope: CoroutineScope): JsonObject {
        if (!core.settings.value.pairing) throw LookoutError("pairing_closed", 403, "Pairing is off. Turn on \"Accept pairing requests\" in Setup on the phone")
        val name = rawName.orEmpty().filter { it.isLetterOrDigit() || it in " ._-" }.trim().take(40)
        if (name.isEmpty()) throw LookoutError("invalid_param", 400, "Give a name of 1 to 40 letters, digits, spaces, dots, dashes or underscores")
        val now = core.clock.now()
        if (denials[remote].orEmpty().count { now - it < BLOCK_MS } >= MAX_DENIALS) {
            throw LookoutError("pair_blocked", 429, "The person denied $MAX_DENIALS requests from this address. Wait ${BLOCK_MS / 60_000} minutes")
        }
        requests.values.removeAll { now - it.createdAt > timeoutMs + KEEP_MS }
        if (requests.values.any { !it.outcome.isCompleted }) throw LookoutError("pair_busy", 409, "Another pairing request waits on the phone")

        val r = Request(LookoutCore.newToken().take(22), "%04d".format(SecureRandom().nextInt(10_000)), name, remote, now)
        requests[r.id] = r
        scope.launch {
            var outcome = Outcome.EXPIRED
            try {
                val text = "$name at $remote wants to use this camera.\n\nCode ${r.code}\n\nAllow only when the agent shows the same code."
                outcome = when (core.human.ask(text, listOf(ALLOW, DENY), timeoutMs, heading = "Pairing request")) {
                    ALLOW -> Outcome.ALLOWED
                    DENY -> Outcome.DENIED
                    else -> Outcome.EXPIRED
                }
            } finally {
                if (outcome == Outcome.DENIED) synchronized(this@Pairing) { denials[remote] = denials[remote].orEmpty().filter { now - it < BLOCK_MS } + core.clock.now() }
                core.events.add("pair", buildJsonObject { put("name", name); put("address", remote); put("outcome", outcome.name.lowercase()) })
                r.outcome.complete(outcome)
            }
        }
        return buildJsonObject {
            put("pair_id", r.id)
            put("code", r.code)
            put("expires_in_ms", timeoutMs)
        }
    }

    /**
     * Waits up to [waitMs] for the answer. An allowed request gives its token one time, and only to
     * the address that asked. A Tailscale address is authenticated by WireGuard, so the token is bound to it.
     */
    suspend fun result(id: String, remote: String, waitMs: Long): JsonObject {
        fun missing() = LookoutError("pair_not_found", 404, "No pairing request '$id' from this address")
        val r = requests[id]?.takeIf { it.remote == remote } ?: throw missing()
        val outcome = withTimeoutOrNull(waitMs.coerceAtLeast(1)) { r.outcome.await() }
            ?: return buildJsonObject { put("state", "pending") }
        requests.remove(id) ?: throw missing()
        return when (outcome) {
            Outcome.DENIED -> throw LookoutError("pair_denied", 403, "The person denied the request")
            Outcome.EXPIRED -> throw LookoutError("pair_expired", 410, "Nobody answered on the phone in time")
            Outcome.ALLOWED -> {
                val address = Lan.parse(remote)?.takeIf(Lan::isTailnet)?.hostAddress
                val token = tokens.addClient(r.name, address, core.clock.now())
                buildJsonObject {
                    put("state", "approved")
                    put("token", token)
                    put("name", r.name)
                    put("device", core.deviceName)
                    address?.let { put("bound_to", it) }
                }
            }
        }
    }

    companion object {
        const val ALLOW = "Allow"
        const val DENY = "Deny"
        const val MAX_DENIALS = 3
        const val BLOCK_MS = 600_000L
        private const val KEEP_MS = 300_000L
    }
}
