package com.kelofred.keloray.core

import com.kelofred.keloray.canon
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Replays cases generated from keloray-control (tools/gen_golden.py) against
 * the Kotlin port, so both implementations produce identical output.
 */
class GoldenTest {
    @Suppress("UNCHECKED_CAST")
    private val golden: Map<String, Any?> by lazy {
        val text = javaClass.classLoader!!.getResource("golden.json")!!.readText()
        JsonUtil.parseObject(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun cases(key: String) = golden[key] as List<Map<String, Any?>>

    @Test
    fun sunChannelsMatchPython() {
        val all = cases("sun")
        for (c in all) {
            val dt = (c["dt"] as List<*>).map { (it as Number).toInt() }
            val loc = c["loc"].asMap()!!
            val frame = Solar.sunChannels(
                LocalDateTime.of(dt[0], dt[1], dt[2], dt[3], dt[4]),
                latitudeDeg = loc["latitude_deg"].asDouble()!!,
                longitudeDeg = loc["longitude_deg"].asDouble()!!,
                utcOffsetHours = loc["utc_offset_hours"].asDouble()!!,
                luxScale = c["lux_scale"].asDouble()!!,
                cctBumpK = c["cct_bump_k"].asDouble()!!,
            )
            assertEquals("sun $dt $loc", canon(c["frame"]), canon(frame))
        }
        assert(all.size >= 10)
    }

    @Test
    fun solarElevationMatchesPython() {
        for (c in cases("elevation")) {
            val e = Solar.solarElevation(
                (c["doy"] as Number).toInt(), c["hour"].asDouble()!!, c["lat"].asDouble()!!,
                c["lon"].asDouble()!!, c["utc"].asDouble()!!,
            )
            assertEquals("elevation $c", c["elev"].asDouble()!!, e, 1e-9)
        }
    }

    @Test
    fun reefChannelsMatchPython() {
        for (c in cases("reef")) {
            val kw = c["kw"].asMap()!!
            val frame = Reef.reefChannels(
                c["elev"].asDouble()!!,
                caps = kw["caps"].asMap(),
                intensityScale = kw["intensity_scale"].asDouble() ?: 1.0,
                moonlight = kw["moonlight"].asDouble() ?: 0.0,
            )
            assertEquals("reef ${c["elev"]} $kw", canon(c["frame"]), canon(frame))
        }
    }

    @Test
    fun schemaNormalizationMatchesPython() {
        val schemas = golden["schemas"].asMap()!!
        for (c in cases("normalize")) {
            val schema = schemas[c["schema"] as String]
            val out = SchemaNormalizer.normalize(c["attrs"].asMap()!!, schema)
            assertEquals("normalize ${c["schema"]} ${c["attrs"]}", canon(c["out"]), canon(out))
        }
    }
}
