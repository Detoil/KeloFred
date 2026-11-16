package com.kelofred.keloray.ui

import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.Scenes
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/** A MockWebServer that answers like keloray-control's REST API. */
class FakeKelorayServer {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<Pair<String, Any?>>()

    private val schedules = CopyOnWriteArrayList(
        listOf(
            mapOf(
                "name" to "weekday wake-up", "did" to "BULB1", "scene" to "sunrise",
                "params" to mapOf("seconds" to 1200), "cron" to mapOf("hour" to 6, "minute" to 30, "day_of_week" to "mon-fri"),
                "at" to null, "enabled" to true,
            ),
            mapOf(
                "name" to "reef daylight", "did" to "CORAL1", "scene" to "reef_day",
                "params" to mapOf("moonlight" to 8, "caps" to mapOf("uv1" to 60, "uv2" to 60)),
                "cron" to mapOf("hour" to 0, "minute" to 5), "at" to null, "enabled" to true,
            ),
        ),
    )

    private val coralSchema = mapOf(
        "entities" to listOf(
            mapOf(
                "attrs" to listOf(
                    mapOf("name" to "Switch", "display_name" to "Power", "data_type" to "bool", "type" to "status_writable"),
                    mapOf("name" to "Cold_white", "display_name" to "Cool white", "data_type" to "uint16", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 1000)),
                    mapOf("name" to "Royal_blue", "display_name" to "Royal blue", "data_type" to "uint16", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 1000)),
                    mapOf("name" to "Sky_blue", "data_type" to "uint16", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 1000)),
                    mapOf("name" to "UV1", "data_type" to "uint8", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 255)),
                    mapOf("name" to "UV2", "data_type" to "uint8", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 255)),
                    mapOf("name" to "Deep_red", "data_type" to "uint16", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 1000)),
                    mapOf("name" to "Green", "data_type" to "uint16", "type" to "status_writable", "uint_spec" to mapOf("min" to 0, "max" to 1000)),
                    mapOf("name" to "Water_temp", "data_type" to "uint16", "type" to "status_readonly", "uint_spec" to mapOf("min" to 0, "max" to 500)),
                ),
            ),
        ),
    )

    private val coralState = linkedMapOf<String, Any?>(
        "Switch" to true, "Cold_white" to 300, "Royal_blue" to 820, "Sky_blue" to 640, "UV1" to 150,
        "UV2" to 130, "Deep_red" to 90, "Green" to 120, "Water_temp" to 257,
    )

    private fun json(body: Any?, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(JsonUtil.stringify(body))

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val body = request.body.readUtf8().takeIf { it.isNotEmpty() }?.let { JsonUtil.parse(it) }
                requests += "${request.method} $path" to body
                return when {
                    path == "/api/devices" -> json(
                        listOf(
                            mapOf("did" to "CORAL1", "product_key" to "pk_al", "mac" to "", "alias" to "Coral tank", "product_name" to "Keloray AL100", "is_online" to true, "raw" to emptyMap<String, Any>()),
                            mapOf("did" to "BULB1", "product_key" to "pk_bulb", "mac" to "", "alias" to "Living room", "product_name" to "Smart bulb", "is_online" to true, "raw" to emptyMap<String, Any>()),
                            mapOf("did" to "BULB2", "product_key" to "pk_bulb", "mac" to "", "alias" to "Study lamp", "product_name" to "Smart bulb", "is_online" to false, "raw" to emptyMap<String, Any>()),
                        ),
                    )
                    path == "/api/devices/CORAL1" -> json(mapOf("did" to "CORAL1", "attrs" to coralState, "scene_running" to true))
                    path.startsWith("/api/devices/BULB") && path.count { it == '/' } == 3 -> json(
                        mapOf("did" to path.substringAfterLast('/'), "attrs" to mapOf("onOff" to 1, "lum" to 72, "temperature" to 35, "hsv" to mapOf("h" to 30, "s" to 60, "v" to 72)), "scene_running" to false),
                    )
                    path.endsWith("/discover") -> json(mapOf("did" to "CORAL1", "product_key" to "pk_al", "datapoints" to coralSchema, "state" to coralState))
                    path == "/api/scenes" -> json(Scenes.all.map { mapOf("name" to it.name, "description" to it.description, "defaults" to it.defaults) })
                    path == "/api/schedules" && request.method == "GET" -> json(schedules)
                    path == "/api/schedules" -> {
                        @Suppress("UNCHECKED_CAST")
                        val rule = body as Map<String, Any?>
                        schedules.removeIf { it["name"] == rule["name"] }
                        schedules += rule
                        json(mapOf("ok" to true))
                    }
                    else -> json(mapOf("ok" to true))
                }
            }
        }
        server.start()
        return server.url("/").toString()
    }

    fun shutdown() = server.shutdown()
}
