package com.kelofred.keloray.data

import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.SchemaNormalizer
import com.kelofred.keloray.core.asDouble
import com.kelofred.keloray.core.asMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Region -> Gizwits OpenAPI host (from the app bundle: api / usapi / euapi). */
val REGIONS = linkedMapOf(
    "cn" to "https://api.gizwitsapi.com",
    "us" to "https://usapi.gizwitsapi.com",
    "eu" to "https://euapi.gizwitsapi.com",
)

val REGION_LABELS = mapOf("cn" to "China", "us" to "Americas", "eu" to "Europe")

/**
 * Public Application-Id candidates extracted from the Keloray app bundle. The
 * live one is whichever answers POST /app/users with a token; see [GizwitsClient.probeAppIds].
 */
val CANDIDATE_APP_IDS = listOf(
    "b04a7fb790fd42b69026721f1930ad4e",
    "f089fe16912a4680bf9b276b121581b0",
    "afb8f41dbb274cb19d0fdf472945fbf3",
    "f50095a20a2f4152a3685a01a863ec5a",
)

data class AuthInfo(val token: String, val uid: String?, val expireAt: Long)

/**
 * Kotlin port of keloray-control's GizwitsClient: the same Gizwits OpenAPI v1
 * calls the official app makes, against your own account.
 *
 *   POST /app/users                 anonymous register (used to probe App-Ids)
 *   POST /app/login                 username/password login
 *   GET  /app/bindings              bound devices
 *   GET  /app/devdata/{did}/latest  latest datapoint values
 *   POST /app/control/{did}         {"attrs": {...}}
 *   GET  /app/datapoint?product_key datapoint schema
 */
class GizwitsClient(
    val appId: String,
    val region: String = "cn",
    var token: String? = null,
    var uid: String? = null,
    var expireAt: Long = 0,
    baseUrl: String? = null,
    private val http: OkHttpClient = defaultHttp,
    /** Called whenever a new token is obtained, so it can be persisted. */
    private val onAuth: (AuthInfo) -> Unit = {},
    /** Stored username/password, used to log in again when the token expires. */
    private val credentials: (() -> Pair<String, String>?)? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val base: String = baseUrl ?: REGIONS[region] ?: throw IllegalArgumentException(
        "region must be one of ${REGIONS.keys}",
    )

    private val authLock = Mutex()
    private val didPk = ConcurrentHashMap<String, String>()
    private val pkSchema = ConcurrentHashMap<String, Any>()

    // -- low level ---------------------------------------------------------

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: Any? = null,
        authed: Boolean = true,
        allowReauth: Boolean = true,
    ): Any? {
        if (authed) refreshIfExpired()
        val (code, parsed) = withContext(Dispatchers.IO) {
            val url = base.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
            val rb = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("X-Gizwits-Application-Id", appId)
            if (authed) token?.let { rb.header("X-Gizwits-User-token", it) }
            val payload = if (body != null || method == "POST" || method == "PUT") {
                JsonUtil.stringify(body ?: emptyMap<String, Any?>()).toRequestBody(JSON)
            } else {
                null
            }
            rb.method(method, payload)
            http.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ctype = resp.header("Content-Type").orEmpty()
                val value: Any? = if (ctype.contains("json") || text.startsWith("{") || text.startsWith("[")) {
                    runCatching { JsonUtil.parse(text) }.getOrDefault(text)
                } else {
                    text
                }
                resp.code to value
            }
        }
        if (code >= 400) {
            if (authed && allowReauth && isTokenError(code, parsed) && credentials?.invoke() != null) {
                relogin(staleToken = token)
                return request(method, path, query, body, authed, allowReauth = false)
            }
            throw ApiException(code, parsed, "Gizwits API error $code: ${describe(parsed)}")
        }
        return parsed
    }

    private fun isTokenError(code: Int, body: Any?): Boolean {
        if (code !in 400..403) return false
        val m = body.asMap() ?: return false
        val ec = m["error_code"].asDouble()?.toInt()
        val msg = (m["error_message"] ?: m["detail_message"] ?: "").toString().lowercase()
        return ec == 9004 || ec == 9006 || msg.contains("token")
    }

    private fun expired() = expireAt > 0 && nowSeconds() >= expireAt - 300

    private suspend fun refreshIfExpired() {
        if (!expired() || credentials?.invoke() == null) return
        authLock.withLock {
            if (expired()) credentials.invoke()?.let { (u, p) -> login(u, p) }
        }
    }

    /** Log in again unless another caller already replaced [staleToken]. */
    private suspend fun relogin(staleToken: String?) = authLock.withLock {
        if (token != staleToken) return@withLock
        val (u, p) = credentials?.invoke() ?: return@withLock
        login(u, p)
    }

    // -- auth --------------------------------------------------------------

    /** Anonymous Gizwits user (cannot see your devices; used for probing). */
    suspend fun registerAnonymous(phoneId: String = "keloray-control"): Map<String, Any?> {
        val data = request(
            "POST", "app/users", authed = false,
            body = mapOf("phone_id" to phoneId, "lang" to "en"),
        ).asMap() ?: emptyMap()
        applyAuth(data)
        return data
    }

    /** Log in with the same username/password you use in the Keloray app. */
    suspend fun login(username: String, password: String): Map<String, Any?> {
        val data = request(
            "POST", "app/login", authed = false,
            body = mapOf("username" to username, "password" to password, "lang" to "en"),
        ).asMap() ?: emptyMap()
        applyAuth(data)
        return data
    }

    private fun applyAuth(data: Map<String, Any?>) {
        val t = data["token"] as? String ?: return
        token = t
        uid = (data["uid"] as? String) ?: uid
        expireAt = data["expire_at"].asDouble()?.toLong() ?: 0
        onAuth(AuthInfo(t, uid, expireAt))
    }

    // -- devices -----------------------------------------------------------

    suspend fun bindings(limit: Int = 50, skip: Int = 0): List<Device> {
        val data = request("GET", "app/bindings", mapOf("limit" to "$limit", "skip" to "$skip")).asMap()
        @Suppress("UNCHECKED_CAST")
        val list = (data?.get("devices") as? List<Map<String, Any?>>).orEmpty()
        return list.map { Device.fromGizwits(it) }.also { devices ->
            devices.forEach { if (it.did.isNotEmpty() && it.productKey.isNotEmpty()) didPk[it.did] = it.productKey }
        }
    }

    /** Latest datapoint values: {attr: value, ...}. */
    suspend fun latest(did: String): Map<String, Any?> {
        val data = request("GET", "app/devdata/$did/latest")
        val m = data.asMap() ?: return emptyMap()
        return m["attr"].asMap() ?: m
    }

    /**
     * Write datapoints, e.g. control(did, {"onOff": 1, "lum": 80}). Canonical
     * attrs are first normalized to the product's real datapoint schema.
     */
    suspend fun control(did: String, attrs: Map<String, Any?>): Any? {
        val normalized = SchemaNormalizer.normalize(attrs, schemaFor(did))
        return request("POST", "app/control/$did", body = mapOf("attrs" to normalized))
    }

    /** Datapoint schema for a product (names, types, ranges, enums). */
    suspend fun datapoints(productKey: String): Any? =
        request("GET", "app/datapoint", mapOf("product_key" to productKey), authed = false)

    // -- schema-aware normalization ----------------------------------------

    fun rememberProductKey(did: String, productKey: String) {
        if (did.isNotEmpty() && productKey.isNotEmpty()) didPk[did] = productKey
    }

    private suspend fun productKeyFor(did: String): String? {
        didPk[did]?.let { return it }
        runCatching { bindings() }.onFailure { return null }
        return didPk[did]
    }

    suspend fun schemaFor(did: String): Any? {
        val pk = productKeyFor(did) ?: return null
        pkSchema[pk]?.let { return it }
        val schema = runCatching { datapoints(pk) }.getOrNull() ?: return null
        pkSchema[pk] = schema
        return schema
    }

    /** Force a re-fetch of a device's product key + schema. */
    suspend fun refreshSchema(did: String): Any? {
        didPk.remove(did)?.let { pkSchema.remove(it) }
        return schemaFor(did)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        fun describe(payload: Any?): String {
            val m = payload.asMap() ?: return payload?.toString()?.take(300).orEmpty()
            val msg = m["error_message"] ?: m["detail_message"] ?: m["detail"] ?: m["message"]
            val code = m["error_code"]
            return when {
                msg != null && code != null -> "$msg (code $code)"
                msg != null -> msg.toString()
                else -> JsonUtil.stringify(m).take(300)
            }
        }

        /**
         * Return the candidate App-Ids that the cloud accepts in [region]: a
         * valid Application-Id gets a token from POST /app/users.
         */
        suspend fun probeAppIds(
            region: String,
            candidates: List<String> = CANDIDATE_APP_IDS,
            http: OkHttpClient = defaultHttp,
            baseUrl: String? = null,
        ): List<String> = coroutineScope {
            candidates.map { id ->
                async {
                    val ok = runCatching {
                        GizwitsClient(id, region, baseUrl = baseUrl, http = http)
                            .registerAnonymous()["token"] != null
                    }.getOrDefault(false)
                    if (ok) id else null
                }
            }.awaitAll().filterNotNull()
        }
    }
}
