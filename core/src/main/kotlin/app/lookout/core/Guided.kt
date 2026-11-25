package app.lookout.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Guided shots. Each one runs in the long-lived core scope, not in the call that asked for it, so a client
 * that stops the call does not stop the shot: the agent fetches the result with wait_for(condition=request).
 * Only one request can wait for Ready at a time. A finished request stays for [KEEP_MS], at most [MAX_KEPT] of them.
 */
class GuidedRequests(private val clock: Clock) {
    /** One guided call. [expiresAt] is when the prompt stops waiting for Ready. */
    class Request(val id: String, val tool: String, val expiresAt: Long) {
        internal val outcome = CompletableDeferred<Result<CapResult>>()
        @Volatile internal var released = false
        @Volatile internal var finishedAt: Long? = null

        /** The person tapped Ready, so the prompt is gone and the shot runs. */
        fun release() {
            released = true
        }

        /** The outcome, or null when the request did not end within [timeoutMs]. A failure holds a [LookoutError]. */
        suspend fun await(timeoutMs: Long): Result<CapResult>? =
            if (outcome.isCompleted) outcome.await() else withTimeoutOrNull(timeoutMs) { outcome.await() }
    }

    private val requests = LinkedHashMap<String, Request>()
    private val next = AtomicLong(1)
    @Volatile private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** True while a request waits for Ready or its shot runs. */
    val active: Boolean get() = synchronized(this) { requests.values.any { !it.outcome.isCompleted } }

    /** Starts [work] in the core scope. Throws when another request still waits for Ready. */
    fun launch(tool: String, expiresAt: Long, work: suspend (Request) -> CapResult): Request {
        val scope = scope ?: throw LookoutError("not_started", 503, "Lookout is not running")
        val req = synchronized(this) {
            prune()
            requests.values.firstOrNull { !it.released && !it.outcome.isCompleted }?.let {
                throw LookoutError("guide_busy", 409, "Guided ${it.tool} ${it.id} still waits for Ready on the phone. Call wait_for(condition=request, request_id=${it.id}).")
            }
            Request("g${next.getAndIncrement()}", tool, expiresAt).also { requests[it.id] = it }
        }
        scope.launch {
            val r = try {
                Result.success(work(req))
            } catch (e: LookoutError) {
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(LookoutError("guided_failed", 500, "The guided $tool failed: ${e.message ?: e::class.simpleName}"))
            }
            req.finishedAt = clock.now()
            req.outcome.complete(r)
        }.invokeOnCompletion {
            // The scope can end before the body runs. The request must still end, or it blocks the next one.
            if (!req.outcome.isCompleted) {
                req.finishedAt = clock.now()
                req.outcome.complete(Result.failure(LookoutError("guided_failed", 500, "The guided $tool stopped because Lookout stopped")))
            }
        }
        return req
    }

    fun get(id: String): Request = synchronized(this) {
        prune()
        requests[id]
    } ?: throw LookoutError("request_not_found", 404, "No guided request '$id'. A finished request stays for ${KEEP_MS / 60_000} minutes.")

    private fun prune() {
        val now = clock.now()
        requests.values.removeAll { r -> r.finishedAt?.let { now - it > KEEP_MS } == true }
        val finished = requests.values.filter { it.finishedAt != null }
        finished.take(maxOf(0, finished.size - MAX_KEPT)).forEach { requests.remove(it.id) }
    }

    companion object {
        /** As long as the image_url in a result works. */
        const val KEEP_MS = Retention.URL_LIFE_MS
        const val MAX_KEPT = 16
    }
}
