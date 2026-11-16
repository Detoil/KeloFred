package com.kelofred.keloray.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Dynamic JSON helpers.
 *
 * The Gizwits API (and the keloray-control server) deal in free-form JSON
 * whose shape depends on the product, so the app works with plain Kotlin
 * values the same way the Python code works with dicts:
 *
 *  - objects -> Map<String, Any?> (insertion order preserved)
 *  - arrays  -> List<Any?>
 *  - numbers -> Long when integral, otherwise Double
 *  - strings, booleans, null as themselves
 */
object JsonUtil {
    private val pretty = Json { prettyPrint = true }

    fun parse(text: String): Any? = fromElement(Json.parseToJsonElement(text))

    fun parseObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return parse(text) as? Map<String, Any?>
            ?: throw IllegalArgumentException("expected a JSON object")
    }

    fun fromElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().apply {
            for ((k, v) in e) put(k, fromElement(v))
        }
        is JsonArray -> e.map { fromElement(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull ?: e.content
        }
    }

    fun toElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Map<*, *> -> JsonObject(
            LinkedHashMap<String, JsonElement>().apply {
                for ((k, value) in v) put(k.toString(), toElement(value))
            },
        )
        is Iterable<*> -> JsonArray(v.map { toElement(it) })
        is Array<*> -> JsonArray(v.map { toElement(it) })
        is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Enum<*> -> JsonPrimitive(v.name)
        else -> JsonPrimitive(v.toString())
    }

    fun stringify(v: Any?): String = toElement(v).toString()

    fun prettify(v: Any?): String = pretty.encodeToString(JsonElement.serializer(), toElement(v))
}

/** Numeric view of a loosely-typed JSON value (Python's isinstance(x, (int, float))). */
fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

/**
 * Python's round(): ties go to the even neighbour. Matching it keeps the
 * Kotlin port's output identical to keloray-control for the same inputs.
 */
fun pyRound(x: Double): Int = kotlin.math.round(x).toInt()

/**
 * Turn a user-typed value into the most specific type, mirroring the CLI's
 * key=value coercion: int, then float, then true/false, then JSON for values
 * starting with { or [, otherwise the raw string.
 */
fun coerceValue(raw: String): Any? {
    val v = raw.trim()
    v.toLongOrNull()?.let { return it }
    v.toDoubleOrNull()?.let { return it }
    when (v.lowercase()) {
        "true" -> return true
        "false" -> return false
        "null", "none" -> return null
    }
    if (v.isNotEmpty() && (v[0] == '{' || v[0] == '[')) {
        runCatching { return JsonUtil.parse(v) }
    }
    return v
}

/** Render a parameter value for an editable text field (inverse of [coerceValue]). */
fun formatValue(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Map<*, *>, is Iterable<*> -> JsonUtil.stringify(v)
    else -> v.toString()
}
