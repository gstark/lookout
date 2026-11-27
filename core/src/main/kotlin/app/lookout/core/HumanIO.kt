package app.lookout.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Text to speech. Android uses TextToSpeech. */
fun interface Speaker {
    /** Returns true when the text was spoken. */
    suspend fun speak(text: String): Boolean
}

/**
 * A question for the person, shown under [heading]. A [guide] prompt shows beside the live preview
 * instead of as a full-screen card: a shot setup (with the optional example [image], a JPEG) or a point request.
 */
class Prompt(
    val id: Long,
    val text: String,
    val buttons: List<String>,
    val deadlineMs: Long,
    val image: ByteArray? = null,
    val guide: Boolean = false,
    val heading: String? = null,
    /** The phone shows the bubble level while this prompt shows. */
    val showLevel: Boolean = false,
    /** The box that the part must fill: the phone draws the grid and this box over the preview. */
    val fit: NRect? = null,
)

/** A guided shot that is taken and waits for the agent: the preview shows [frame] until then. */
class Held(val requestId: String, val frame: Frame)

/**
 * The human-in-the-loop channel. The phone UI shows [current] as a full-screen card and calls
 * [answer] when the person taps a button.
 */
class HumanIO(private val speaker: Speaker, private val clock: Clock = Clock.SYSTEM) {
    private val ids = AtomicLong(1)
    private val _current = MutableStateFlow<Prompt?>(null)
    val current: StateFlow<Prompt?> = _current.asStateFlow()
    private val pending = mutableMapOf<Long, CompletableDeferred<String>>()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    suspend fun say(text: String): Boolean {
        _toasts.tryEmit(text)
        return speaker.speak(text)
    }

    /** Shows [text] with [buttons] and returns the tapped choice, or null on timeout. */
    suspend fun ask(text: String, buttons: List<String>, timeoutMs: Long, heading: String? = null): String? =
        show(text, buttons, timeoutMs, null, guide = false, heading = heading)

    /** Shows [text] under [heading] beside the preview and returns the tapped choice, or null on timeout. */
    suspend fun guide(
        heading: String, text: String, image: ByteArray?, buttons: List<String>, timeoutMs: Long, showLevel: Boolean = false, fit: NRect? = null,
    ): String? = show(text, buttons, timeoutMs, image, guide = true, heading = heading, showLevel = showLevel, fit = fit)

    private val _shots = MutableSharedFlow<Unit>(extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A shot that the phone took on its own, such as at a green level. The phone buzzes, so the person knows. */
    val shots: SharedFlow<Unit> = _shots.asSharedFlow()

    fun shotTaken() {
        _shots.tryEmit(Unit)
    }

    private val _held = MutableStateFlow<Held?>(null)

    /** The shot that the preview shows in place of the live view, from its capture until the agent gets it. */
    val held: StateFlow<Held?> = _held.asStateFlow()

    /** Freezes the preview on [frame], so the person knows the guided shot [requestId] is taken. */
    fun hold(requestId: String, frame: Frame) {
        _held.value = Held(requestId, frame)
    }

    /** Lets the preview go live again. Returns false when [requestId] is not held. */
    fun release(requestId: String): Boolean {
        val h = _held.value ?: return false
        return h.requestId == requestId && _held.compareAndSet(h, null)
    }

    private suspend fun show(
        text: String, buttons: List<String>, timeoutMs: Long, image: ByteArray?, guide: Boolean, heading: String? = null, showLevel: Boolean = false,
        fit: NRect? = null,
    ): String? {
        val prompt = Prompt(ids.getAndIncrement(), text, buttons.ifEmpty { listOf("OK") }, clock.now() + timeoutMs, image, guide, heading, showLevel, fit)
        val answer = CompletableDeferred<String>()
        // A new shot setup needs the live preview.
        if (guide) _held.value = null
        synchronized(pending) { pending[prompt.id] = answer }
        _current.value = prompt
        try {
            return withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            synchronized(pending) { pending.remove(prompt.id) }
            _current.compareAndSet(prompt, null)
        }
    }

    fun answer(promptId: Long, choice: String) {
        synchronized(pending) { pending[promptId] }?.complete(choice)
    }

    fun dismiss() {
        _current.value?.let { p -> synchronized(pending) { pending[p.id] }?.cancel() }
        _current.value = null
    }
}
