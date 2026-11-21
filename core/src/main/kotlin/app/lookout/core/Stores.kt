package app.lookout.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A small persistent string store. Android backs it with DataStore. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Persistent named byte blobs, for baseline images. */
interface BlobStore {
    fun put(name: String, bytes: ByteArray)
    fun get(name: String): ByteArray?
    fun delete(name: String)
    fun clear()
}

class DirectoryBlobStore(private val dir: File) : BlobStore {
    init { dir.mkdirs() }
    private fun file(name: String) = File(dir, "$name.jpg")
    override fun put(name: String, bytes: ByteArray) = file(name).writeBytes(bytes)
    override fun get(name: String) = file(name).takeIf { it.exists() }?.readBytes()
    override fun delete(name: String) { file(name).delete() }
    override fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

class MemoryBlobStore : BlobStore {
    private val map = ConcurrentHashMap<String, ByteArray>()
    override fun put(name: String, bytes: ByteArray) { map[name] = bytes }
    override fun get(name: String) = map[name]
    override fun delete(name: String) { map.remove(name) }
    override fun clear() = map.clear()
}

val LookoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val NAME = Regex("[a-z0-9][a-z0-9_-]{0,39}")

fun checkName(name: String, what: String): String {
    if (!NAME.matches(name)) throw LookoutError("invalid_name", 400, "$what name must match ${NAME.pattern}, got '$name'")
    return name
}

@Serializable
data class RoiDefaults(val lens: String? = null, val zoom: Double? = null, val focus: FocusSetting? = null, val torch: Boolean? = null) {
    fun toSettings() = CameraSettings(lens = lens, zoom = zoom, focus = focus, torch = torch)
}

@Serializable
data class Roi(val name: String, val rect: NRect, val defaults: RoiDefaults? = null)

/** Named regions, persisted as JSON in the key-value store. */
class RoiRegistry(private val kv: KeyValueStore) {
    private val serializer = ListSerializer(Roi.serializer())
    private val lock = Any()
    private var rois: List<Roi> = kv.get(KEY)?.let { runCatching { LookoutJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun list(): List<Roi> = synchronized(lock) { rois }

    fun get(name: String): Roi? = synchronized(lock) { rois.firstOrNull { it.name == name } }

    fun require(name: String): Roi = get(name) ?: throw LookoutError("roi_not_found", 404, "No ROI named '$name'")

    fun put(roi: Roi): Roi = synchronized(lock) {
        checkName(roi.name, "ROI")
        rois = rois.filterNot { it.name == roi.name } + roi
        save()
        roi
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        val before = rois.size
        rois = rois.filterNot { it.name == name }
        save()
        rois.size != before
    }

    fun rename(from: String, to: String): Roi = synchronized(lock) {
        val roi = require(from).copy(name = checkName(to, "ROI"))
        rois = rois.filterNot { it.name == from || it.name == to } + roi
        save()
        roi
    }

    private fun save() = kv.put(KEY, LookoutJson.encodeToString(serializer, rois))

    companion object { const val KEY = "rois" }
}

@Serializable
data class BaselineInfo(val name: String, val createdAt: Long, val frameId: Long, val width: Int, val height: Int, val roi: String? = null)

/** Full-resolution reference frames. Metadata lives in the key-value store, JPEGs in the blob store. */
class Baselines(private val kv: KeyValueStore, private val blobs: BlobStore) {
    private val serializer = ListSerializer(BaselineInfo.serializer())
    private val lock = Any()
    private var infos: List<BaselineInfo> = kv.get(KEY)?.let { runCatching { LookoutJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun list(): List<BaselineInfo> = synchronized(lock) { infos }

    fun get(name: String): BaselineInfo? = synchronized(lock) { infos.firstOrNull { it.name == name } }

    fun jpeg(name: String): ByteArray? = blobs.get(name)

    fun save(info: BaselineInfo, jpeg: ByteArray) = synchronized(lock) {
        checkName(info.name, "Baseline")
        blobs.put(info.name, jpeg)
        infos = infos.filterNot { it.name == info.name } + info
        kv.put(KEY, LookoutJson.encodeToString(serializer, infos))
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        val found = infos.any { it.name == name }
        blobs.delete(name)
        infos = infos.filterNot { it.name == name }
        kv.put(KEY, LookoutJson.encodeToString(serializer, infos))
        found
    }

    fun clear() = synchronized(lock) {
        blobs.clear()
        infos = emptyList()
        kv.put(KEY, null)
    }

    companion object { const val KEY = "baselines" }
}

/** Composite images (diffs, contact sheets, wait results), kept so JSON responses can link to them. */
class CompositeStore(private val capacity: Int = 64) {
    private val map = object : LinkedHashMap<String, ByteArray>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > capacity
    }
    private var next = 1L

    @Synchronized
    fun put(jpeg: ByteArray): String {
        val id = "c${next++}"
        map[id] = jpeg
        return id
    }

    @Synchronized
    fun get(id: String): ByteArray? = map[id]
}
