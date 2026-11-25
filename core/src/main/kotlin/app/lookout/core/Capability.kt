package app.lookout.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class PType(val jsonType: String) {
    STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean"), OBJECT("object"), STRING_ARRAY("array"),
}

/** One parameter of a capability. REST and MCP schemas both come from this. */
data class Param(
    val name: String,
    val type: PType,
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null,
    /** A JSON schema for OBJECT params, used as is in MCP tool schemas. */
    val schema: JsonObject? = null,
)

class CallContext(val via: String, val remote: String? = null)

class ImageOut(val jpeg: ByteArray, val id: String? = null)

/** What a capability returns: JSON (the metadata for image results) and an optional image. */
class CapResult(val json: JsonObject, val image: ImageOut? = null)

/**
 * A capability, defined once. The server turns each one into an MCP tool and a REST route,
 * so the two cannot drift.
 */
class Capability(
    val name: String,
    val description: String,
    val params: List<Param>,
    /** IMAGE capabilities answer REST with image/jpeg and the JSON in X-Lookout-Meta. */
    val kind: Kind,
    /** A call wakes Lookout from sleep and restarts its idle timer. */
    val wakes: Boolean = true,
    val handler: suspend (Args, CallContext) -> CapResult,
) {
    enum class Kind { IMAGE, JSON }

    init {
        require(params.map { it.name }.toSet().size == params.size) { "Duplicate params in $name" }
    }

    suspend fun call(args: Args, ctx: CallContext): CapResult {
        args.checkKnown(params.map { it.name }.toSet() + Args.REST_ONLY)
        params.filter { it.required }.forEach { p ->
            if (args.raw(p.name) == null) throw LookoutError("missing_param", 400, "Missing required parameter '${p.name}'")
        }
        params.filter { it.enum != null }.forEach { p ->
            args.string(p.name)?.let { v ->
                if (v !in p.enum!!) throw LookoutError("invalid_param", 400, "'${p.name}' must be one of ${p.enum.joinToString()}")
            }
        }
        return handler(args, ctx)
    }
}

/**
 * Arguments from MCP (typed JSON) or REST (strings). Accessors accept both, so a REST query
 * string needs no separate coercion step.
 */
class Args(private val obj: JsonObject) {
    fun raw(name: String): JsonElement? = obj[name]?.takeUnless { it is JsonNull }

    fun string(name: String): String? = when (val e = raw(name)) {
        null -> null
        is JsonPrimitive -> e.content
        else -> throw bad(name, "a string")
    }

    fun long(name: String): Long? = raw(name)?.let { e ->
        (e as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: throw bad(name, "an integer")
    }

    fun int(name: String): Int? = long(name)?.toInt()

    fun double(name: String): Double? = raw(name)?.let { e ->
        (e as? JsonPrimitive)?.doubleOrNull ?: throw bad(name, "a number")
    }

    fun bool(name: String): Boolean? = raw(name)?.let { e ->
        val p = e as? JsonPrimitive ?: throw bad(name, "a boolean")
        p.booleanOrNull ?: when (p.content.lowercase()) {
            "1", "yes", "on" -> true
            "0", "no", "off" -> false
            else -> throw bad(name, "a boolean")
        }
    }

    fun strings(name: String): List<String>? = when (val e = raw(name)) {
        null -> null
        is JsonArray -> e.map { it.jsonPrimitive.content }
        is JsonPrimitive -> e.content.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> throw bad(name, "a list of strings")
    }

    fun obj(name: String): JsonObject? = when (val e = raw(name)) {
        null -> null
        is JsonObject -> e
        is JsonPrimitive -> runCatching { LookoutJson.parseToJsonElement(e.content) as JsonObject }.getOrElse { throw bad(name, "a JSON object") }
        else -> throw bad(name, "a JSON object")
    }

    /** A rect as {x,y,w,h}, [x,y,w,h] or "x,y,w,h". */
    fun rect(name: String): NRect? {
        val e = raw(name)?.let(::unquoteJson) ?: return null
        val nums: List<Double?> = when (e) {
            is JsonObject -> listOf("x", "y", "w", "h").map { (e[it] as? JsonPrimitive)?.doubleOrNull }
            is JsonArray -> e.map { (it as? JsonPrimitive)?.doubleOrNull }
            is JsonPrimitive -> e.content.split(",").map { it.trim().toDoubleOrNull() }
        }
        if (nums.size != 4 || nums.any { it == null }) throw bad(name, "a rect {x,y,w,h} in 0..1")
        return NRect(nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!)
    }

    /** Focus as {mode, distance, x, y} or "auto" / "manual:<diopters>" / "point:<x>,<y>". */
    fun focus(name: String): FocusSetting? = when (val e = raw(name)?.let(::unquoteJson)) {
        null -> null
        is JsonObject -> runCatching { LookoutJson.decodeFromJsonElement(FocusSetting.serializer(), e) }.getOrElse { ex ->
            throw (ex as? LookoutError) ?: bad(name, "{mode:\"auto\"}, {mode:\"manual\", distance}, or {mode:\"point\", x, y}")
        }
        is JsonPrimitive -> FocusSetting.parse(e.content)
        else -> throw bad(name, "{mode:\"auto\"}, {mode:\"manual\", distance}, or {mode:\"point\", x, y}")
    }

    /** Models often send an object or array as a JSON string. Accept that. */
    private fun unquoteJson(e: JsonElement): JsonElement {
        val p = e as? JsonPrimitive ?: return e
        if (!p.isString) return e
        val t = p.content.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return e
        return runCatching { LookoutJson.parseToJsonElement(t) }.getOrDefault(e)
    }

    fun checkKnown(known: Set<String>) {
        val unknown = obj.keys - known
        if (unknown.isNotEmpty()) throw LookoutError("unknown_param", 400, "Unknown parameter(s): ${unknown.joinToString()}")
    }

    private fun bad(name: String, what: String) = LookoutError("invalid_param", 400, "'$name' must be $what")

    companion object {
        /** Parameters that only shape the REST response. */
        val REST_ONLY = setOf("format", "inline", "token")
        val EMPTY = Args(JsonObject(emptyMap()))
    }
}
