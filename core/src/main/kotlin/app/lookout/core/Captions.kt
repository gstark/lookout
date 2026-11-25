package app.lookout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Text that the agent shows over the camera view, from [fromMs] until [untilMs] (null: until cleared). */
data class Caption(val id: Long, val text: String, val fromMs: Long, val untilMs: Long?) {
    fun showsAt(ms: Long): Boolean = ms >= fromMs && (untilMs == null || ms < untilMs)
}

/**
 * The captions the agent showed, as a timeline. Images draw the caption that showed when their frame
 * was taken, so a frame from history or a recording carries the text of that moment. One caption shows at a time.
 */
class Captions(private val clock: Clock = Clock.SYSTEM) {
    private val ids = AtomicLong(1)
    private val lock = Any()
    private val timeline = ArrayDeque<Caption>()
    private val _current = MutableStateFlow<Caption?>(null)

    /** The caption for the phone preview. The preview clears it at [Caption.untilMs]. */
    val current: StateFlow<Caption?> = _current.asStateFlow()

    /** Shows [text] now. It replaces the caption that shows. */
    fun show(text: String, durationMs: Long?): Caption = synchronized(lock) {
        val now = clock.now()
        end(now)
        val c = Caption(ids.getAndIncrement(), text, now, durationMs?.let { now + it })
        timeline.addLast(c)
        while (timeline.size > MAX_TIMELINE) timeline.removeFirst()
        _current.value = c
        c
    }

    /** Ends the caption that shows. With [id], it ends it only if that caption still shows. Returns the ended caption. */
    fun clear(id: Long? = null): Caption? = synchronized(lock) {
        val last = timeline.lastOrNull() ?: return null
        if (id != null && last.id != id) return null
        _current.value = null
        end(clock.now())
    }

    /** The caption that showed at [ms], or null. */
    fun at(ms: Long): Caption? = synchronized(lock) { timeline.lastOrNull { it.showsAt(ms) } }

    private fun end(now: Long): Caption? {
        val last = timeline.lastOrNull() ?: return null
        if (!last.showsAt(now)) return null
        return last.copy(untilMs = now).also { timeline[timeline.size - 1] = it }
    }

    companion object {
        const val MAX_TEXT = 80
        const val MAX_DURATION_MS = 3_600_000L
        const val MAX_TIMELINE = 500
    }
}
