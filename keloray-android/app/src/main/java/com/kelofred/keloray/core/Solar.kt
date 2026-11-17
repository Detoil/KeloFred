package com.kelofred.keloray.core

import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tropical sun tracker: a line-for-line port of keloray-control's solar.py
 * (NOAA solar-position equations + daylight CCT / illuminance curves). See
 * research/tropical-sunlight.md for the derivation and citations.
 */
object Solar {
    const val DEFAULT_LAT = 1.35
    const val DEFAULT_LON = 103.82
    const val DEFAULT_UTC = 8.0

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    fun equationOfTimeMinutes(dayOfYear: Int): Double {
        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)
        return 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
    }

    fun declinationDeg(dayOfYear: Int): Double =
        -23.44 * cos(rad(360.0 / 365.0 * (dayOfYear + 10)))

    fun solarElevation(
        dayOfYear: Int,
        hourLocal: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        utcOffsetHours: Double,
    ): Double {
        val decl = rad(declinationDeg(dayOfYear))
        val lat = rad(latitudeDeg)
        val eot = equationOfTimeMinutes(dayOfYear)
        val lstm = 15.0 * utcOffsetHours
        val timeCorrection = 4.0 * (longitudeDeg - lstm) + eot
        val trueSolarTimeMin = hourLocal * 60.0 + timeCorrection
        val hourAngle = rad(trueSolarTimeMin / 4.0 - 180.0)
        var sinElev = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)
        sinElev = max(-1.0, min(1.0, sinElev))
        return deg(asin(sinElev))
    }

    fun cctFromElevation(elevDeg: Double): Double {
        if (elevDeg <= -6.0) return 2000.0
        if (elevDeg >= 25.0) {
            val t = min(1.0, (elevDeg - 25.0) / 35.0)
            return 5700.0 + t * (6500.0 - 5700.0)
        }
        val t = max(0.0, min(1.0, (elevDeg + 6.0) / 31.0))
        val eased = t * t * (3 - 2 * t)
        return 2000.0 + eased * (5700.0 - 2000.0)
    }

    fun cctToTemperature(cctK: Double): Int {
        val t = max(0.0, min(1.0, (cctK - 2000.0) / (6500.0 - 2000.0)))
        return pyRound(t * 100)
    }

    fun luxFromElevation(elevDeg: Double): Double {
        if (elevDeg <= -6.0) return 0.0
        if (elevDeg <= 0.0) {
            val t = (elevDeg + 6.0) / 6.0
            return 400.0 * t.pow(2)
        }
        val s = sin(rad(elevDeg))
        return 128000.0 * s.pow(1.15)
    }

    fun luxToLum(lux: Double): Int {
        if (lux <= 1.0) return 1
        val v = 20.0 * log10(lux)
        return max(1, min(100, pyRound(v)))
    }

    fun elevationAt(now: LocalDateTime, latitudeDeg: Double, longitudeDeg: Double, utcOffsetHours: Double): Double =
        solarElevation(
            now.dayOfYear,
            now.hour + now.minute / 60.0,
            latitudeDeg,
            longitudeDeg,
            utcOffsetHours,
        )

    /**
     * White-light frame for an RGB/white bulb: {onOff, lum, temperature[, hsv]}.
     * [luxScale] / [cctBumpK] implement the wet-season dimming/cooling.
     */
    fun sunChannels(
        now: LocalDateTime,
        latitudeDeg: Double = DEFAULT_LAT,
        longitudeDeg: Double = DEFAULT_LON,
        utcOffsetHours: Double = DEFAULT_UTC,
        luxScale: Double = 1.0,
        cctBumpK: Double = 0.0,
    ): Map<String, Any?> {
        val elev = elevationAt(now, latitudeDeg, longitudeDeg, utcOffsetHours)
        if (elev <= -6.0) return linkedMapOf("onOff" to 0, "lum" to 1, "temperature" to 0)
        val lux = luxFromElevation(elev) * luxScale
        val cct = cctFromElevation(elev) + cctBumpK
        val lum = luxToLum(lux)
        val out = linkedMapOf<String, Any?>("onOff" to 1, "lum" to lum, "temperature" to cctToTemperature(cct))
        if (elev > -6.0 && elev < 6.0) {
            if (elev >= 0.0) {
                val strength = pyRound((1.0 - elev / 6.0) * 40)
                out["hsv"] = linkedMapOf("h" to 30, "s" to strength, "v" to lum)
            } else {
                val strength = pyRound((1.0 - (elev + 6.0) / 6.0) * 35)
                out["hsv"] = linkedMapOf("h" to 220, "s" to strength, "v" to lum)
            }
        }
        return out
    }
}
