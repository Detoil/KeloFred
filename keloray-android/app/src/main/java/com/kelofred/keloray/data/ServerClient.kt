package com.kelofred.keloray.data

import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.asMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for keloray-control's REST API (`python -m keloray serve`). In this
 * mode scenes and schedules run on the server, so they keep going when the
 * phone is asleep or away.
 */
class ServerClient(baseUrl: String, private val http: OkHttpClient = defaultHttp) {
    val base: HttpUrl = normalizeUrl(baseUrl).toHttpUrl()

    private suspend fun call(method: String, segments: List<String>, body: Any? = null): Any? =
        withContext(Dispatchers.IO) {
            val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
            val payload = if (method == "GET" || (method == "DELETE" && body == null)) {
                null
            } else {
                JsonUtil.stringify(body ?: emptyMap<String, Any?>()).toRequestBody(JSON)
            }
            val req = Request.Builder().url(url).method(method, payload).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val parsed: Any? = runCatching { JsonUtil.parse(text) }.getOrDefault(text)
                if (resp.code >= 400) {
                    throw ApiException(resp.code, parsed, "Server error ${resp.code}: ${describe(parsed)}")
                }
                parsed
            }
        }

    private fun api(vararg s: String) = listOf("api") + s

    suspend fun devices(): List<Device> =
        (call("GET", api("devices")) as? List<*>).orEmpty().mapNotNull { it.asMap()?.let(Device::fromServer) }

    suspend fun state(did: String): DeviceState {
        val m = call("GET", api("devices", did)).asMap() ?: emptyMap()
        return DeviceState(did, m["attrs"].asMap() ?: emptyMap(), m["scene_running"] == true)
    }

    suspend fun discover(did: String): Discovery {
        val m = call("GET", api("devices", did, "discover")).asMap() ?: emptyMap()
        return Discovery(did, (m["product_key"] as? String).orEmpty(), m["datapoints"], m["state"].asMap() ?: emptyMap())
    }

    suspend fun control(did: String, attrs: Map<String, Any?>) {
        call("POST", api("devices", did, "control"), mapOf("attrs" to attrs))
    }

    suspend fun power(did: String, on: Boolean) {
        call("POST", api("devices", did, "power"), mapOf("on" to on))
    }

    suspend fun startScene(did: String, scene: String, params: Map<String, Any?>) {
        call("POST", api("devices", did, "scene"), mapOf("scene" to scene, "params" to params))
    }

    suspend fun stopScene(did: String) {
        call("POST", api("devices", did, "scene", "stop"), emptyMap<String, Any?>())
    }

    suspend fun scenes(): List<SceneInfo> =
        (call("GET", api("scenes")) as? List<*>).orEmpty().mapNotNull { it.asMap()?.let(SceneInfo::fromServer) }

    suspend fun schedules(): List<Rule> =
        (call("GET", api("schedules")) as? List<*>).orEmpty().mapNotNull { it.asMap()?.let(Rule::fromMap) }

    suspend fun saveSchedule(rule: Rule) {
        call("POST", api("schedules"), rule.toMap())
    }

    suspend fun deleteSchedule(name: String) {
        call("DELETE", api("schedules", name))
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** "192.168.1.5:8000" -> "http://192.168.1.5:8000/" */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) throw IllegalArgumentException("Enter the server address")
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            if (!u.endsWith("/")) u += "/"
            return u
        }

        fun describe(payload: Any?): String {
            val m = payload.asMap() ?: return payload?.toString()?.take(300).orEmpty()
            val detail = m["detail"]
            val dm = detail.asMap()
            return when {
                dm != null -> "Gizwits ${dm["gizwits_status"]}: ${GizwitsClient.describe(dm["body"])}"
                detail is List<*> -> detail.joinToString { (it.asMap()?.get("msg") ?: it).toString() }
                detail != null -> detail.toString()
                else -> JsonUtil.stringify(m).take(300)
            }
        }
    }
}
