package com.kelofred.keloray.core

import kotlin.math.max
import kotlin.math.min

/**
 * Reef spectral model: sun elevation -> per-channel coral-light intensities.
 * Port of keloray-control's reef.py.
 *
 * Brightness follows the sun; the spectrum shifts the way it does underwater:
 * red-leaning at low sun (dawn/dusk), blue/UV-dominant at high sun (water
 * absorbs red, blue penetrates deepest).
 */
object Reef {
    private fun smooth(x: Double): Double {
        val t = max(0.0, min(1.0, x))
        return t * t * (3 - 2 * t)
    }

    /**
     * @param caps per-channel maximum 0..100 for coral acclimation (default 100)
     * @param intensityScale overall multiplier (wet-season / cloud dimming)
     * @param moonlight faint blue level 0..100 held at night instead of off
     * @return {"onOff": 0|1, "channels": {logical: 0..100}}
     */
    fun reefChannels(
        elevDeg: Double,
        caps: Map<String, Any?>? = null,
        intensityScale: Double = 1.0,
        moonlight: Double = 0.0,
    ): Map<String, Any?> {
        val c = caps ?: emptyMap()
        fun capped(ch: Map<String, Double>): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (k in CORAL_CHANNELS) {
                val cap = c[k].asDouble() ?: 100.0
                out[k] = pyRound(max(0.0, min(100.0, ch[k] ?: 0.0)) * cap / 100.0)
            }
            return out
        }

        if (elevDeg <= -3.0) {
            if (moonlight > 0) {
                return linkedMapOf(
                    "onOff" to 1,
                    "channels" to capped(mapOf("rb" to moonlight, "sb" to moonlight * 0.6)),
                )
            }
            return linkedMapOf("onOff" to 0, "channels" to capped(emptyMap()))
        }

        var intensity = Solar.luxToLum(Solar.luxFromElevation(elevDeg)) / 100.0
        intensity *= max(0.0, intensityScale)

        val b = smooth(elevDeg / 50.0)
        val raw = mapOf(
            "cw" to intensity * (40 + 25 * (1 - b)),
            "rb" to intensity * (40 + 60 * b),
            "sb" to intensity * (30 + 50 * b),
            "uv1" to intensity * (5 + 70 * b),
            "uv2" to intensity * (5 + 65 * b),
            "dr" to intensity * (10 + 75 * (1 - b)),
            "g" to intensity * 25,
        )
        return linkedMapOf("onOff" to 1, "channels" to capped(raw))
    }
}
