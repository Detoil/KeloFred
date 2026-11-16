package com.kelofred.keloray.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScenesTest {
    private fun envAt(t: LocalDateTime) = SceneEnv(now = { t })

    @Test
    fun registryMatchesPython() {
        assertEquals(
            listOf(
                "solid", "fade", "sunrise", "sunset", "color_loop", "breathe", "candle",
                "thunderstorm", "tropics", "reef_day", "reef_storm",
            ),
            Scenes.all.map { it.name },
        )
        assertEquals(SceneKind.SPECTRAL, Scenes.get("reef_day").kind)
        assertTrue(Scenes.get("tropics").continuous)
        assertTrue(!Scenes.get("sunrise").continuous)
    }

    @Test
    fun fadeYieldsFrames() = runTest {
        val frames = Scenes.flowFor("fade", mapOf("start" to 0, "end" to 100, "seconds" to 1, "tick" to 0.5)).toList()
        assertEquals(mapOf("onOff" to 1), frames.first())
        assertEquals(listOf(0, 50, 100), frames.drop(1).map { it["lum"] })
    }

    @Test
    fun sunriseRampsUpAndSunsetEndsOff() = runTest {
        val up = Scenes.flowFor("sunrise", mapOf("seconds" to 10, "tick" to 2.0)).toList()
        assertEquals(mapOf("onOff" to 1, "lum" to 1), up.first())
        assertEquals(100, up.last()["lum"])
        assertEquals(100, up.last()["temperature"])
        val down = Scenes.flowFor("sunset", mapOf("seconds" to 10, "tick" to 2.0)).toList()
        assertEquals(mapOf("onOff" to 0), down.last())
        assertEquals(1, down[down.size - 2]["lum"])
    }

    @Test
    fun solidHonoursOptionalParams() = runTest {
        val f = Scenes.flowFor("solid", mapOf("lum" to null, "hsv" to mapOf("h" to 10, "s" to 20, "v" to 30), "on" to "true")).toList()
        assertEquals(listOf(mapOf("onOff" to 1, "hsv" to mapOf("h" to 10, "s" to 20, "v" to 30))), f)
    }

    @Test
    fun colorLoopCyclesHue() = runTest {
        val frames = Scenes.flowFor("color_loop", mapOf("period" to 4, "tick" to 1.0)).take(6).toList()
        @Suppress("UNCHECKED_CAST")
        val hues = frames.drop(1).map { (it["hsv"] as Map<String, Any?>)["h"] }
        assertEquals(listOf(0, 90, 180, 270, 0), hues)
    }

    @Test
    fun thunderstormIsDimWithFlashesAndSeedReproducible() = runTest {
        val params = mapOf(
            "base_lum" to 8, "flash_lum" to 100, "min_gap" to 0.5, "max_gap" to 1.5,
            "double_flash_chance" to 0.5, "tick" to 0.5, "seed" to 1234,
        )
        val a = Scenes.flowFor("thunderstorm", params).take(40).toList()
        val b = Scenes.flowFor("thunderstorm", params).take(40).toList()
        assertEquals(a, b)
        val lums = a.map { it["lum"] as Int }
        assertTrue(lums.any { it <= 12 })
        assertTrue(lums.any { it >= 90 })
        assertTrue(lums.all { it <= 12 || it >= 90 })
    }

    @Test
    fun tropicsFollowsTheClock() = runTest {
        val noon = LocalDateTime.of(2026, 6, 28, 13, 0)
        val f = Scenes.flowFor("tropics", emptyMap(), envAt(noon)).take(1).toList().single()
        assertEquals(Solar.sunChannels(noon), f)
        val night = Scenes.flowFor("tropics", emptyMap(), envAt(noon.withHour(4))).take(1).toList().single()
        assertEquals(0, night["onOff"])
    }

    @Test
    fun tropicsWetSeasonDims() = runTest {
        val dec = LocalDateTime.of(2026, 12, 2, 13, 0)
        val dry = Scenes.flowFor("tropics", emptyMap(), envAt(dec)).take(1).toList().single()
        val wet = Scenes.flowFor("tropics", mapOf("wet_season" to true), envAt(dec)).take(1).toList().single()
        assertTrue((wet["lum"] as Int) < (dry["lum"] as Int))
        // outside the wet months nothing changes
        val jun = dec.withMonth(6)
        assertEquals(
            Scenes.flowFor("tropics", emptyMap(), envAt(jun)).take(1).toList(),
            Scenes.flowFor("tropics", mapOf("wet_season" to true), envAt(jun)).take(1).toList(),
        )
    }

    @Test
    fun reefDayEmitsChannelsAndMoonlight() = runTest {
        val noon = LocalDateTime.of(2026, 6, 28, 13, 0)
        val day = Scenes.flowFor("reef_day", mapOf("caps" to mapOf("uv1" to 60)), envAt(noon)).take(1).toList().single()
        @Suppress("UNCHECKED_CAST")
        val ch = day["channels"] as Map<String, Int>
        assertEquals(CORAL_CHANNELS, ch.keys.toList())
        assertTrue(ch.getValue("uv1") <= 60)
        val night = Scenes.flowFor("reef_day", mapOf("moonlight" to 8), envAt(noon.withHour(2))).take(1).toList().single()
        assertEquals(1, night["onOff"])
    }

    @Test
    fun reefStormUsesWhiteAndBlue() = runTest {
        val frames = Scenes.flowFor("reef_storm", mapOf("seed" to 3, "min_gap" to 0.5, "max_gap" to 1.0)).take(30).toList()
        @Suppress("UNCHECKED_CAST")
        val channels = frames.map { it["channels"] as Map<String, Int> }
        assertTrue(channels.all { it["dr"] == 0 && it["uv1"] == 0 })
        assertTrue(channels.any { it["cw"] == 100 })
        assertEquals(21, channels.first()["rb"]) // int(15 * 1.4)
    }
}
