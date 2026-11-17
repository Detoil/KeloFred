package com.kelofred.keloray.data

import com.kelofred.keloray.canon
import com.kelofred.keloray.core.JsonUtil
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ServerClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = ServerClient(server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: Any?) = server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JsonUtil.stringify(body)))

    @Test
    fun normalizesUrls() {
        assertEquals("http://192.168.1.5:8000/", ServerClient.normalizeUrl(" 192.168.1.5:8000 "))
        assertEquals("https://pi.local/", ServerClient.normalizeUrl("https://pi.local"))
    }

    @Test
    fun devicesAndState() = runTest {
        ok(listOf(mapOf("did" to "D1", "product_key" to "pk", "alias" to "Tank", "product_name" to "AL100", "is_online" to true, "raw" to mapOf("x" to 1))))
        val d = client.devices().single()
        assertEquals(Device("D1", "pk", "", "Tank", "AL100", true, mapOf("x" to 1L)), d)
        assertEquals("/api/devices", server.takeRequest().path)

        ok(mapOf("did" to "D1", "attrs" to mapOf("lum" to 80), "scene_running" to true))
        val s = client.state("D1")
        assertTrue(s.sceneRunning)
        assertEquals(80L, s.attrs["lum"])
    }

    @Test
    fun controlSceneAndSchedulePayloads() = runTest {
        ok(mapOf("ok" to true))
        client.control("D1", mapOf("channels" to mapOf("rb" to 50)))
        server.takeRequest().let {
            assertEquals("/api/devices/D1/control", it.path)
            assertEquals(canon(mapOf("attrs" to mapOf("channels" to mapOf("rb" to 50)))), canon(JsonUtil.parse(it.body.readUtf8())))
        }

        ok(mapOf("ok" to true))
        client.startScene("D1", "reef_day", mapOf("moonlight" to 8))
        server.takeRequest().let {
            assertEquals("/api/devices/D1/scene", it.path)
            assertEquals(canon(mapOf("scene" to "reef_day", "params" to mapOf("moonlight" to 8))), canon(JsonUtil.parse(it.body.readUtf8())))
        }

        ok(mapOf("ok" to true))
        client.stopScene("D1")
        assertEquals("/api/devices/D1/scene/stop", server.takeRequest().path)

        ok(mapOf("ok" to true))
        client.deleteSchedule("weekday wake-up")
        server.takeRequest().let {
            assertEquals("DELETE", it.method)
            assertEquals("/api/schedules/weekday%20wake-up", it.path)
        }

        ok(mapOf("ok" to true))
        client.saveSchedule(Rule("wake", "D1", "sunrise", mapOf("seconds" to 1200), mapOf("hour" to 6, "minute" to 30, "day_of_week" to "mon,tue")))
        server.takeRequest().let {
            val m = JsonUtil.parseObject(it.body.readUtf8())
            assertEquals("wake", m["name"])
            assertEquals(null, m["at"])
            assertEquals("mon,tue", (m["cron"] as Map<*, *>)["day_of_week"])
        }
    }

    @Test
    fun scenesListUsesLocalKinds() = runTest {
        ok(listOf(mapOf("name" to "reef_day", "description" to "Tropical reef sun.\nMore text", "defaults" to mapOf("tick" to 60))))
        val s = client.scenes().single()
        assertEquals("Tropical reef sun.", s.description)
        assertEquals(com.kelofred.keloray.core.SceneKind.SPECTRAL, s.kind)
    }

    @Test
    fun surfacesServerErrors() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502).setHeader("Content-Type", "application/json")
                .setBody("""{"detail":{"gizwits_status":400,"body":{"error_code":9004,"error_message":"token invalid!"}}}"""),
        )
        try {
            client.power("D1", true)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(502, e.status)
            assertTrue(e.message, e.message!!.contains("token invalid!"))
        }
    }
}
