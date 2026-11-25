package app.lookout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Who or what changed the sleep state. */
enum class SleepCause(val wire: String) { IDLE("idle"), DONE("done"), AGENT("agent"), PHONE("phone") }

/**
 * Decides when Lookout sleeps to save battery. Asleep, the light is off, the camera stays closed
 * and the phone screen can turn off. The server keeps listening, so the next agent call or a touch
 * on the phone wakes it.
 *
 * Lookout sleeps when nothing used it for the idle time, or soon after an agent calls done. It
 * never sleeps while work is active (a wait, a recording, a question, an open camera call), and
 * not within [PHONE_GRACE_MS] of a touch on the phone.
 */
class Sleep(private val clock: Clock) {
    private val _asleep = MutableStateFlow(false)
    val asleep: StateFlow<Boolean> = _asleep.asStateFlow()

    @Volatile private var lastAgentAt = clock.now()
    /** No touch yet, so the phone grace time does not hold off a done right after the start. */
    @Volatile private var lastPhoneAt = 0L
    @Volatile private var lastBusyAt = clock.now()

    /** True after an agent called done, until the next agent call. */
    @Volatile var released = false
        private set

    /** An agent call. Returns true when it woke Lookout. */
    fun agentActive(): Boolean {
        lastAgentAt = clock.now()
        released = false
        return wake()
    }

    /** A touch on the phone. Returns true when it woke Lookout. */
    fun phoneActive(): Boolean {
        lastPhoneAt = clock.now()
        return wake()
    }

    /** The agent is done for now. */
    fun release() {
        released = true
    }

    /** Why Lookout sleeps now, or null when it must stay awake. [idleMs] null turns the idle timer off. */
    fun due(idleMs: Long?, busy: Boolean): SleepCause? {
        val now = clock.now()
        if (busy) lastBusyAt = now
        if (_asleep.value || busy || now - lastPhoneAt < PHONE_GRACE_MS) return null
        if (released) return SleepCause.DONE
        if (idleMs != null && now - maxOf(lastAgentAt, lastPhoneAt, lastBusyAt) >= idleMs) return SleepCause.IDLE
        return null
    }

    fun fallAsleep() {
        released = false
        _asleep.value = true
    }

    private fun wake(): Boolean = _asleep.compareAndSet(true, false)

    companion object {
        const val PHONE_GRACE_MS = 60_000L
    }
}
