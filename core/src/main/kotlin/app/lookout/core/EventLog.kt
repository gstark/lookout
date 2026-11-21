package app.lookout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Event(val id: Long, val ts: Long, val type: String, val data: JsonObject = JsonObject(emptyMap()))

/** The last [capacity] events: requests, detections and human answers. */
class EventLog(private val clock: Clock = Clock.SYSTEM, private val capacity: Int = 500) {
    private val events = ArrayDeque<Event>()
    private val lastId = MutableStateFlow(0L)
    val latestId: StateFlow<Long> = lastId.asStateFlow()

    fun add(type: String, data: JsonObject = JsonObject(emptyMap())): Event = synchronized(events) {
        val e = Event(lastId.value + 1, clock.now(), type, data)
        events.addLast(e)
        while (events.size > capacity) events.removeFirst()
        lastId.value = e.id
        e
    }

    fun since(sinceId: Long, types: Set<String>? = null): List<Event> = synchronized(events) {
        events.filter { it.id > sinceId && (types == null || it.type in types) }
    }

    fun recent(limit: Int): List<Event> = synchronized(events) { events.takeLast(limit) }

    /** Returns events after [sinceId], waiting up to [waitMs] for the first one. */
    suspend fun await(sinceId: Long, waitMs: Long): List<Event> {
        if (waitMs > 0 && lastId.value <= sinceId) {
            withTimeoutOrNull(waitMs) { lastId.first { it > sinceId } }
        }
        return since(sinceId)
    }
}
