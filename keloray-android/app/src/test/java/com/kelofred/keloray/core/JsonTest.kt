package com.kelofred.keloray.core

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun coerceMatchesCli() {
        assertEquals(600L, coerceValue("600"))
        assertEquals(0.4, coerceValue("0.4"))
        assertEquals(true, coerceValue("true"))
        assertEquals(false, coerceValue("False"))
        assertEquals(null, coerceValue("none"))
        assertEquals(mapOf("h" to 280L, "s" to 100L, "v" to 100L), coerceValue("""{"h":280,"s":100,"v":100}"""))
        assertEquals(listOf(11L, 12L, 1L), coerceValue("[11,12,1]"))
        assertEquals("sunrise", coerceValue("sunrise"))
        assertEquals("{broken", coerceValue("{broken"))
    }

    @Test
    fun formatRoundTrips() {
        for (v in listOf<Any?>(1L, 2.0, true, "x", mapOf("uv1" to 60L), listOf(11L, 12L, 1L))) {
            assertEquals(v, coerceValue(formatValue(v)))
        }
        assertEquals("", formatValue(null))
    }

    @Test
    fun parseKeepsOrderAndTypes() {
        val m = JsonUtil.parseObject("""{"b":1,"a":2.5,"c":null,"d":[true,"x"]}""")
        assertEquals(listOf("b", "a", "c", "d"), m.keys.toList())
        assertEquals(1L, m["b"])
        assertEquals(2.5, m["a"])
        assertEquals(null, m["c"])
        assertEquals(listOf(true, "x"), m["d"])
    }

    @Test
    fun pythonRounding() {
        assertEquals(2, pyRound(2.5))
        assertEquals(4, pyRound(3.5))
        assertEquals(-2, pyRound(-2.5))
        assertEquals(3, pyRound(2.6))
    }
}
