package com.kelofred.keloray.core

import kotlin.math.max
import kotlin.math.min

/** One datapoint from a product's Gizwits schema, flattened for display/mapping. */
data class DatapointInfo(
    val name: String,
    val displayName: String?,
    val type: String?,
    val dataType: String?,
    val range: Pair<Double, Double>?,
    val enum: List<Any?>?,
    val raw: Map<String, Any?>,
) {
    val isNumeric: Boolean
        get() = listOf(dataType, type).any { t ->
            t != null && listOf("uint", "int", "number", "float", "double").any { t.lowercase().contains(it) }
        }

    val writable: Boolean
        get() = type?.lowercase()?.contains("writable") ?: true
}

/**
 * Schema-aware attr normalization: a port of the `_normalized` family in
 * keloray-control's client.py.
 *
 * Real Gizwits light products vary: some expose a single packed colour
 * datapoint, others separate H/S/V datapoints, and names/ranges differ. Given
 * the product's datapoint schema, canonical keys (onOff, lum, temperature, hsv)
 * are renamed/rescaled onto the real datapoints. Anything not confidently
 * mapped is passed through unchanged.
 */
object SchemaNormalizer {

    /** Flatten a schema into {lower_name: descriptor}. First occurrence wins. */
    fun index(schema: Any?): LinkedHashMap<String, Map<String, Any?>> {
        val index = LinkedHashMap<String, Map<String, Any?>>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    val name = node["name"]
                    if (name is String && (
                            "type" in node || "data_type" in node ||
                                "uint_spec" in node || "enum" in node
                            )
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        index.putIfAbsent(name.lowercase(), node as Map<String, Any?>)
                    }
                    for (v in node.values) walk(v)
                }
                is List<*> -> node.forEach { walk(it) }
            }
        }
        walk(schema)
        return index
    }

    fun datapoints(schema: Any?): List<DatapointInfo> = index(schema).map { (lower, attr) ->
        DatapointInfo(
            name = (attr["name"] as? String) ?: lower,
            displayName = attr["display_name"] as? String,
            type = attr["type"]?.toString(),
            dataType = attr["data_type"]?.toString(),
            range = rangeOf(attr),
            enum = attr["enum"] as? List<Any?>,
            raw = attr,
        )
    }

    /** Best-effort (min, max) from top-level min/max, uint_spec or value. */
    fun rangeOf(attr: Map<String, Any?>): Pair<Double, Double>? {
        for (spec in listOf<Any?>(attr, attr["uint_spec"], attr["value"])) {
            if (spec is Map<*, *>) {
                val lo = spec["min"].asDouble()
                val hi = spec["max"].asDouble()
                if (lo != null && hi != null) return lo to hi
            }
        }
        return null
    }

    /** Linear rescale from src to dst range, clamped and rounded. */
    fun scale(value: Double, src: Pair<Double, Double>, dst: Pair<Double, Double>): Int {
        val (slo, shi) = src
        val (dlo, dhi) = dst
        if (shi == slo) return pyRound(dlo)
        val frac = max(0.0, min(1.0, (value - slo) / (shi - slo)))
        return pyRound(dlo + frac * (dhi - dlo))
    }

    private fun colorField(index: Map<String, Map<String, Any?>>, vararg needles: String): String? {
        for ((name, attr) in index) {
            if (needles.all { name.contains(it) }) return (attr["name"] as? String) ?: name
        }
        return null
    }

    fun normalize(attrs: Map<String, Any?>, schema: Any?): Map<String, Any?> {
        if (schema == null) return attrs
        val index = index(schema)
        if (index.isEmpty()) return attrs
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in attrs) {
            val mapped = runCatching { mapAttr(key, value, index) }.getOrElse { mapOf(key to value) }
            out.putAll(mapped)
        }
        return out
    }

    private val ALIASES = mapOf(
        "lum" to listOf("lum", "bright"),
        "temperature" to listOf("temp"),
        "onoff" to listOf("onoff", "switch", "power"),
    )

    private val CANONICAL_SRC = mapOf(
        "lum" to (1.0 to 100.0),
        "temperature" to (0.0 to 100.0),
    )

    fun mapAttr(key: String, value: Any?, index: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val kl = key.lowercase()
        if (kl == "hsv" && value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return mapHsv(value as Map<String, Any?>, index)
        }

        var target: String? = null
        if (key in index) {
            target = key
        } else if (kl in index) {
            target = (index[kl]?.get("name") as? String) ?: key
        } else {
            for (needle in ALIASES[kl].orEmpty()) {
                val hit = colorField(index, needle)
                if (hit != null) {
                    target = hit
                    break
                }
            }
        }
        if (target == null) return mapOf(key to value)

        val attr = index[target.lowercase()] ?: emptyMap()
        val dst = rangeOf(attr)
        val src = CANONICAL_SRC[kl]
        val num = value.asDouble()
        if (num != null && value !is Boolean && dst != null && src != null && dst != src) {
            return mapOf(target to scale(num, src, dst))
        }
        return mapOf(target to value)
    }

    private fun intOf(v: Any?): Int = (v.asDouble() ?: 0.0).toInt()

    fun mapHsv(hsv: Map<String, Any?>, index: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val h = hsv["h"] ?: 0
        val s = hsv["s"] ?: 0
        val v = hsv["v"] ?: 0
        val canonical = linkedMapOf<String, Any?>("h" to intOf(h), "s" to intOf(s), "v" to intOf(v))

        // 1) a single packed colour datapoint
        var packed = colorField(index, "hsv") ?: colorField(index, "color")
        if (packed == null) {
            for ((name, attr) in index) {
                val t = (attr["type"] ?: attr["data_type"] ?: "").toString().lowercase()
                if (t in setOf("object", "json", "struct")) {
                    packed = (attr["name"] as? String) ?: name
                    break
                }
            }
        }
        if (packed != null &&
            listOf("_h", "_s", "_v").none { packed.lowercase().contains(it) } &&
            packed.lowercase() !in setOf("h", "s", "v")
        ) {
            return mapOf(packed to canonical)
        }

        // 2) separate hue / saturation / value datapoints
        fun find(vararg needleSets: Array<String>): String? {
            for (needles in needleSets) colorField(index, *needles)?.let { return it }
            return null
        }
        val hDp = find(arrayOf("hue"), arrayOf("color_h"), arrayOf("_h")) ?: if ("h" in index) "h" else null
        val sDp = find(arrayOf("saturation"), arrayOf("color_s"), arrayOf("_s")) ?: if ("s" in index) "s" else null
        val vDp = find(arrayOf("value"), arrayOf("color_v"), arrayOf("brightness_v"), arrayOf("_v"))
            ?: if ("v" in index) "v" else null

        if (hDp != null && sDp != null && vDp != null) {
            val out = LinkedHashMap<String, Any?>()
            for ((dp, comp, src) in listOf(
                Triple(hDp, h, 0.0 to 360.0),
                Triple(sDp, s, 0.0 to 100.0),
                Triple(vDp, v, 0.0 to 100.0),
            )) {
                val attr = index[dp.lowercase()] ?: emptyMap()
                val dst = rangeOf(attr)
                val name = (attr["name"] as? String) ?: dp
                out[name] = if (dst != null && dst != src) scale(comp.asDouble() ?: 0.0, src, dst) else intOf(comp)
            }
            return out
        }

        // 3) nothing recognisable: canonical hsv unchanged
        return mapOf("hsv" to canonical)
    }
}
