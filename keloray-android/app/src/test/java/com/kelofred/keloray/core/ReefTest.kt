package com.kelofred.keloray.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReefTest {
    @Suppress("UNCHECKED_CAST")
    private fun ch(f: Map<String, Any?>) = f["channels"] as Map<String, Int>

    @Test
    fun nightIsOff() {
        val out = Reef.reefChannels(-12.0)
        assertEquals(0, out["onOff"])
        assertTrue(ch(out).values.all { it == 0 })
    }

    @Test
    fun moonlightKeepsFaintBlue() {
        val out = Reef.reefChannels(-12.0, moonlight = 20.0)
        assertEquals(1, out["onOff"])
        assertTrue(ch(out).getValue("rb") > 0)
    }

    @Test
    fun middayIsBlueDominant() {
        val c = ch(Reef.reefChannels(80.0))
        assertTrue(c.getValue("rb") > c.getValue("dr"))
        assertTrue(c.getValue("uv1") > c.getValue("dr"))
    }

    @Test
    fun dawnIsRedLeaning() {
        val c = ch(Reef.reefChannels(2.0))
        assertTrue(c.getValue("dr") > c.getValue("rb"))
    }

    @Test
    fun capsLimitChannels() {
        val full = ch(Reef.reefChannels(80.0))
        val capped = ch(Reef.reefChannels(80.0, caps = mapOf("rb" to 50)))
        assertTrue(capped.getValue("rb") <= full.getValue("rb") / 2 + 1)
    }
}
