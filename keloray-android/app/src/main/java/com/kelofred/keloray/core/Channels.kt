package com.kelofred.keloray.core

import kotlin.math.max
import kotlin.math.min

/**
 * Spectral channel mapping for multi-channel fixtures (e.g. Keloray AL-series
 * coral lights). Port of keloray-control's channels.py.
 *
 * Logical channels (canonical order):
 *   cw  cool white    rb  royal blue    sb  sky blue
 *   uv1 ultraviolet 1 uv2 ultraviolet 2 dr  deep red    g  green
 */
val CORAL_CHANNELS = listOf("cw", "rb", "sb", "uv1", "uv2", "dr", "g")

val CHANNEL_LABELS = mapOf(
    "cw" to "Cool white",
    "rb" to "Royal blue",
    "sb" to "Sky blue",
    "uv1" to "UV 1",
    "uv2" to "UV 2",
    "dr" to "Deep red",
    "g" to "Green",
)

/** Approximate display colour per channel (ARGB), for slider tracks. */
val CHANNEL_COLORS = mapOf(
    "cw" to 0xFFE8F1FF,
    "rb" to 0xFF2F4BFF,
    "sb" to 0xFF33B5FF,
    "uv1" to 0xFF8A3FFC,
    "uv2" to 0xFFB45CFF,
    "dr" to 0xFFE0303A,
    "g" to 0xFF34C759,
)

/** How one logical channel maps onto the device: real datapoint + numeric range. */
data class ChannelSpec(val name: String, val lo: Double = 0.0, val hi: Double = 100.0) {
    fun toDevice(value: Double): Int {
        val v = max(0.0, min(100.0, value))
        return pyRound(lo + (hi - lo) * (v / 100.0))
    }
}

class ChannelMap(mapping: Map<String, Any?>? = null) {
    val specs: Map<String, ChannelSpec>

    init {
        val s = LinkedHashMap<String, ChannelSpec>()
        for (k in CORAL_CHANNELS) s[k] = ChannelSpec(k)
        mapping?.forEach { (logical, v) -> s[logical] = coerce(logical, v) }
        specs = s
    }

    /** {logical: 0..100} -> {datapoint: scaled int}. Unknown names pass through. */
    fun toAttrs(channels: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((logical, value) in channels) {
            val spec = specs[logical]
            if (spec == null) {
                out[logical] = value
            } else {
                out[spec.name] = spec.toDevice(value.asDouble() ?: 0.0)
            }
        }
        return out
    }

    /** True if any channel was mapped away from its identity default. */
    fun configured(): Boolean = specs.any { (k, s) -> s.name != k || s.lo != 0.0 || s.hi != 100.0 }

    /** Serializable form, the same shape as the `channels:` block in config.yaml. */
    fun toConfig(): Map<String, Any?> = specs.mapValues { (_, s) ->
        linkedMapOf("name" to s.name, "lo" to s.lo, "hi" to s.hi)
    }

    companion object {
        fun coerce(logical: String, v: Any?): ChannelSpec = when (v) {
            is ChannelSpec -> v
            is String -> ChannelSpec(v)
            is Map<*, *> -> ChannelSpec(
                name = (v["name"] as? String) ?: logical,
                lo = (v["lo"] ?: v["min"]).asDouble() ?: 0.0,
                hi = (v["hi"] ?: v["max"]).asDouble() ?: 100.0,
            )
            else -> ChannelSpec(logical)
        }

        /**
         * Best-effort guess of which real datapoint drives each logical channel,
         * from datapoint names like "Royal_blue", "UV1", "deepRed". Channels with
         * no confident match are left out so the user picks them by hand.
         */
        fun autoMap(datapoints: List<DatapointInfo>): Map<String, ChannelSpec> {
            val candidates = datapoints.filter { dp ->
                val n = norm(dp.name)
                val excluded = listOf("onoff", "switch", "power", "mode", "scene", "temp", "lum", "bright", "timer", "fan")
                excluded.none { n.contains(it) } && (dp.isNumeric || dp.range != null)
            }
            val taken = mutableSetOf<String>()
            val out = LinkedHashMap<String, ChannelSpec>()

            fun pick(logical: String, vararg preds: (String, List<String>) -> Boolean) {
                for (p in preds) {
                    val hit = candidates.firstOrNull { it.name !in taken && p(norm(it.name), tokens(it.name)) }
                    if (hit != null) {
                        taken += hit.name
                        val (lo, hi) = hit.range ?: (0.0 to 100.0)
                        out[logical] = ChannelSpec(hit.name, lo, hi)
                        return
                    }
                }
            }

            pick(
                "rb",
                { n, _ -> n.contains("royal") },
                { _, t -> "rb" in t },
            )
            pick(
                "cw",
                { n, _ -> n.contains("coolwhite") || n.contains("coldwhite") },
                { _, t -> "cw" in t },
                { n, _ -> n.contains("white") && !n.contains("warm") },
            )
            pick(
                "sb",
                { n, _ -> n.contains("sky") },
                { _, t -> "sb" in t },
                { n, _ -> n.contains("blue") },
            )
            val uv = { n: String -> n.contains("uv") || n.contains("violet") || n.contains("purple") }
            pick(
                "uv1",
                { n, _ -> uv(n) && n.contains("1") },
                { n, _ -> uv(n) && !n.contains("2") },
            )
            pick(
                "uv2",
                { n, _ -> uv(n) && n.contains("2") },
                { n, _ -> uv(n) },
            )
            pick(
                "dr",
                { n, _ -> n.contains("red") },
                { _, t -> "dr" in t },
            )
            pick(
                "g",
                { n, _ -> n.contains("green") },
                { _, t -> "g" in t || "gr" in t },
            )
            return out
        }

        private fun norm(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

        /** "deepRed_2" -> [deep, red, 2]; "Royal-Blue" -> [royal, blue]. */
        private fun tokens(name: String): List<String> =
            name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .split(Regex("[^A-Za-z0-9]+"))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
    }
}
