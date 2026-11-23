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
)

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
    suspend fun guide(heading: String, text: String, image: ByteArray?, buttons: List<String>, timeoutMs: Long): String? =
        show(text, buttons, timeoutMs, image, guide = true, heading = heading)

    private suspend fun show(text: String, buttons: List<String>, timeoutMs: Long, image: ByteArray?, guide: Boolean, heading: String? = null): String? {
        val prompt = Prompt(ids.getAndIncrement(), text, buttons.ifEmpty { listOf("OK") }, clock.now() + timeoutMs, image, guide, heading)
        val answer = CompletableDeferred<String>()
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
