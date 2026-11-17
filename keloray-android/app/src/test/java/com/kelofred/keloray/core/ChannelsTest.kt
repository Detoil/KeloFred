package com.kelofred.keloray.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {
    @Test
    fun identityMap() {
        val m = ChannelMap()
        assertEquals(mapOf("cw" to 50, "rb" to 100), m.toAttrs(mapOf("cw" to 50, "rb" to 100)))
        assertFalse(m.configured())
    }

    @Test
    fun renameAndScale() {
        val m = ChannelMap(
            mapOf(
                "cw" to "Cold_white",
                "rb" to mapOf("name" to "Royal_blue", "hi" to 1000),
                "uv1" to mapOf("name" to "UV1", "lo" to 0, "hi" to 255),
            ),
        )
        assertEquals(
            mapOf("Cold_white" to 50, "Royal_blue" to 1000, "UV1" to 255),
            m.toAttrs(mapOf("cw" to 50, "rb" to 100, "uv1" to 100)),
        )
        assertTrue(m.configured())
    }

    @Test
    fun unknownChannelPassesThrough() {
        assertEquals(mapOf("mystery" to 42), ChannelMap().toAttrs(mapOf("mystery" to 42)))
    }

    @Test
    fun valuesAreClamped() {
        val m = ChannelMap(mapOf("rb" to mapOf("name" to "RB", "hi" to 1000)))
        assertEquals(mapOf("RB" to 1000), m.toAttrs(mapOf("rb" to 140)))
        assertEquals(mapOf("RB" to 0), m.toAttrs(mapOf("rb" to -5)))
    }

    @Test
    fun configRoundTrip() {
        val m = ChannelMap(mapOf("rb" to ChannelSpec("Royal_blue", 0.0, 1000.0)))
        val again = ChannelMap(JsonUtil.parseObject(JsonUtil.stringify(m.toConfig())))
        assertEquals(m.specs, again.specs)
    }

    private fun dp(name: String, hi: Double = 1000.0) =
        DatapointInfo(name, null, "status_writable", "uint16", 0.0 to hi, null, emptyMap())

    @Test
    fun autoMapRecognisesTypicalNames() {
        val guess = ChannelMap.autoMap(
            listOf(
                DatapointInfo("Switch", null, "status_writable", "bool", null, null, emptyMap()),
                dp("Cold_white"), dp("Royal_blue"), dp("Sky_blue"), dp("UV1", 255.0), dp("UV2", 255.0),
                dp("Deep_red"), dp("Green"), dp("Temp_sensor"),
            ),
        )
        assertEquals("Cold_white", guess["cw"]?.name)
        assertEquals("Royal_blue", guess["rb"]?.name)
        assertEquals("Sky_blue", guess["sb"]?.name)
        assertEquals("UV1", guess["uv1"]?.name)
        assertEquals(255.0, guess["uv1"]?.hi)
        assertEquals("UV2", guess["uv2"]?.name)
        assertEquals("Deep_red", guess["dr"]?.name)
        assertEquals("Green", guess["g"]?.name)
    }

    @Test
    fun autoMapHandlesCamelCaseAndAbbreviations() {
        val guess = ChannelMap.autoMap(listOf(dp("royalBlue"), dp("ch_cw"), dp("violet"), dp("deepRed")))
        assertEquals("royalBlue", guess["rb"]?.name)
        assertEquals("ch_cw", guess["cw"]?.name)
        assertEquals("violet", guess["uv1"]?.name)
        assertEquals("deepRed", guess["dr"]?.name)
        assertEquals(null, guess["g"])
    }
}
