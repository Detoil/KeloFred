package com.kelofred.keloray.engine

import com.kelofred.keloray.core.ChannelMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneRunnerTest {
    private class Writer(val failFirst: Int = 0, val alwaysFail: Boolean = false) {
        val writes = mutableListOf<Pair<String, Map<String, Any?>>>()
        var calls = 0
        suspend fun write(did: String, attrs: Map<String, Any?>) {
            calls++
            if (alwaysFail || calls <= failFirst) throw RuntimeException("boom")
            writes += did to attrs
        }
    }

    private fun TestScope.runner(w: Writer, map: ChannelMap = ChannelMap()) = SceneRunner(
        scope = backgroundScope,
        write = w::write,
        channelMap = { map },
        minIntervalMs = { 0 },
        clockMs = { testScheduler.currentTime },
    )

    @Test
    fun writesEveryFrameAndClearsWhenDone() = runTest {
        val w = Writer()
        val r = runner(w)
        r.start("D1", "fade", mapOf("start" to 0, "end" to 100, "seconds" to 1, "tick" to 0.5))
        assertTrue(r.isRunning("D1"))
        advanceTimeBy(60_000) // backgroundScope work is not counted by advanceUntilIdle
        assertEquals(listOf(mapOf("onOff" to 1), mapOf("lum" to 0), mapOf("lum" to 50), mapOf("lum" to 100)), w.writes.map { it.second })
        assertFalse(r.isRunning("D1"))
    }

    @Test
    fun retriesThenContinues() = runTest {
        val w = Writer(failFirst = 2)
        val r = runner(w)
        r.start("D1", "fade", mapOf("seconds" to 1, "tick" to 0.5))
        advanceTimeBy(60_000) // backgroundScope work is not counted by advanceUntilIdle
        assertEquals(4, w.writes.size)
        assertEquals(6, w.calls)
    }

    @Test
    fun permanentFailureStopsAfterThreshold() = runTest {
        val w = Writer(alwaysFail = true)
        val r = runner(w)
        r.start("D1", "breathe")
        advanceTimeBy(60_000) // backgroundScope work is not counted by advanceUntilIdle
        assertEquals(30, w.calls) // 10 frames x 3 attempts
        assertFalse(r.isRunning("D1"))
    }

    @Test
    fun stopCancelsAndNewSceneReplacesOld() = runTest {
        val w = Writer()
        val r = runner(w)
        r.start("D1", "breathe", mapOf("tick" to 1.0))
        advanceTimeBy(3_500)
        r.start("D1", "candle")
        assertEquals("candle", r.running.value["D1"]?.scene)
        advanceTimeBy(2_000)
        r.stop("D1")
        val n = w.writes.size
        advanceTimeBy(10_000)
        assertEquals(n, w.writes.size)
        assertTrue(r.running.value.isEmpty())
    }

    @Test
    fun spectralFramesAreExpandedThroughTheChannelMap() = runTest {
        val w = Writer()
        val r = runner(w, ChannelMap(mapOf("cw" to "Cold_white", "rb" to mapOf("name" to "Royal_blue", "hi" to 1000))))
        assertEquals(
            mapOf("onOff" to 1, "Cold_white" to 50, "Royal_blue" to 1000),
            r.expand(mapOf("onOff" to 1, "channels" to mapOf("cw" to 50, "rb" to 100)), "D1"),
        )
        assertEquals(mapOf("lum" to 80), r.expand(mapOf("lum" to 80), "D1"))
        r.start("D1", "reef_storm", mapOf("seed" to 1))
        advanceTimeBy(1_000)
        r.stop("D1")
        val first = w.writes.first().second
        assertEquals(210, first["Royal_blue"]) // base rb = int(15 * 1.4) = 21%, of 0..1000
        assertEquals(15, first["Cold_white"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSceneIsRejected() = runTest {
        runner(Writer()).start("D1", "disco")
    }
}
