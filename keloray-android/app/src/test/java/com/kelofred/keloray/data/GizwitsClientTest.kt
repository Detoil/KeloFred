package com.kelofred.keloray.data

import com.kelofred.keloray.canon
import com.kelofred.keloray.core.JsonUtil
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GizwitsClientTest {
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: Any?, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(JsonUtil.stringify(body))

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return handler(request)
            }
        }
    }

    private fun client(token: String? = "tok123", creds: Pair<String, String>? = null, onAuth: (AuthInfo) -> Unit = {}) =
        GizwitsClient("testapp", "cn", token = token, baseUrl = server.url("/").toString(), onAuth = onAuth, credentials = { creds })

    private fun body(r: RecordedRequest) = JsonUtil.parse(r.body.readUtf8())

    @Test
    fun controlSendsAttrsAndHeaders() = runTest {
        route { r ->
            when (r.requestUrl!!.encodedPath) {
                "/app/bindings" -> json(mapOf("devices" to emptyList<Any>()))
                else -> json(mapOf("ok" to true))
            }
        }
        client().control("DID1", mapOf("onOff" to 1, "lum" to 80))
        val ctl = requests.last()
        assertEquals("/app/control/DID1", ctl.requestUrl!!.encodedPath)
        assertEquals("POST", ctl.method)
        assertEquals("testapp", ctl.getHeader("X-Gizwits-Application-Id"))
        assertEquals("tok123", ctl.getHeader("X-Gizwits-User-token"))
        assertEquals(canon(mapOf("attrs" to mapOf("onOff" to 1, "lum" to 80))), canon(body(ctl)))
    }

    @Test
    fun controlIsNormalizedToTheProductSchema() = runTest {
        route { r ->
            when (r.requestUrl!!.encodedPath) {
                "/app/bindings" -> json(mapOf("devices" to listOf(mapOf("did" to "D1", "product_key" to "pk1"))))
                "/app/datapoint" -> {
                    assertEquals("pk1", r.requestUrl!!.queryParameter("product_key"))
                    assertEquals(null, r.getHeader("X-Gizwits-User-token")) // schema call is unauthenticated
                    json(
                        mapOf(
                            "entities" to listOf(
                                mapOf(
                                    "attrs" to listOf(
                                        mapOf("name" to "Brightness", "data_type" to "uint8", "uint_spec" to mapOf("min" to 0, "max" to 255)),
                                        mapOf("name" to "Hue", "type" to "uint16", "uint_spec" to mapOf("min" to 0, "max" to 359)),
                                        mapOf("name" to "Saturation", "type" to "uint8", "uint_spec" to mapOf("min" to 0, "max" to 254)),
                                        mapOf("name" to "Value", "type" to "uint8", "uint_spec" to mapOf("min" to 0, "max" to 254)),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                else -> json(mapOf<String, Any?>())
            }
        }
        val c = client()
        c.control("D1", mapOf("lum" to 100, "hsv" to mapOf("h" to 180, "s" to 50, "v" to 100)))
        c.control("D1", mapOf("lum" to 1))
        val controls = requests.filter { it.requestUrl!!.encodedPath == "/app/control/D1" }
        assertEquals(
            canon(mapOf("attrs" to mapOf("Brightness" to 255, "Hue" to 180, "Saturation" to 127, "Value" to 254))),
            canon(body(controls[0])),
        )
        assertEquals(canon(mapOf("attrs" to mapOf("Brightness" to 0))), canon(body(controls[1])))
        // bindings + schema fetched once, then cached
        assertEquals(1, requests.count { it.requestUrl!!.encodedPath == "/app/datapoint" })
    }

    @Test
    fun loginStoresTokenAndReportsIt() = runTest {
        route { json(mapOf("uid" to "u1", "token" to "abc", "expire_at" to 1_900_000_000L)) }
        var reported: AuthInfo? = null
        val c = client(token = null) { reported = it }
        c.login("user", "pw")
        assertEquals("abc", c.token)
        assertEquals(AuthInfo("abc", "u1", 1_900_000_000L), reported)
        val r = requests.single()
        assertEquals("/app/login", r.requestUrl!!.encodedPath)
        assertEquals(canon(mapOf("username" to "user", "password" to "pw", "lang" to "en")), canon(body(r)))
    }

    @Test
    fun bindingsParseAliasFallbacks() = runTest {
        route {
            json(
                mapOf(
                    "devices" to listOf(
                        mapOf("did" to "D1", "product_key" to "pk", "dev_alias" to "Desk", "is_online" to true),
                        mapOf("did" to "D2", "product_key" to "pk", "remark" to "Tank", "product_name" to "AL100"),
                        mapOf("did" to "D3", "product_key" to "pk", "product_name" to "Bulb"),
                    ),
                ),
            )
        }
        val devs = client().bindings()
        assertEquals(listOf("Desk", "Tank", "Bulb"), devs.map { it.alias })
        assertTrue(devs[0].isOnline)
        assertEquals("limit=50&skip=0", requests.single().requestUrl!!.encodedQuery)
    }

    @Test
    fun expiredTokenLogsInAgainAndRetries() = runTest {
        var tokenSeen = mutableListOf<String?>()
        route { r ->
            when (r.requestUrl!!.encodedPath) {
                "/app/login" -> json(mapOf("uid" to "u1", "token" to "fresh", "expire_at" to 0))
                else -> {
                    tokenSeen += r.getHeader("X-Gizwits-User-token")
                    if (r.getHeader("X-Gizwits-User-token") == "fresh") {
                        json(mapOf("attr" to mapOf("onOff" to 1)))
                    } else {
                        json(mapOf("error_code" to 9004, "error_message" to "token invalid!"), 400)
                    }
                }
            }
        }
        val c = client(token = "stale", creds = "user" to "pw")
        assertEquals(mapOf("onOff" to true.let { 1L }), c.latest("D1"))
        assertEquals(listOf<String?>("stale", "fresh"), tokenSeen)
    }

    @Test
    fun errorsCarryTheGizwitsMessage() = runTest {
        route { json(mapOf("error_code" to 9015, "error_message" to "user not exist"), 400) }
        try {
            client().login("x", "y")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertTrue(e.message!!.contains("user not exist"))
        }
    }

    @Test
    fun probeFindsTheLiveAppId() = runTest {
        route { r ->
            if (r.getHeader("X-Gizwits-Application-Id") == CANDIDATE_APP_IDS[2]) {
                json(mapOf("uid" to "anon", "token" to "t"))
            } else {
                json(mapOf("error_code" to 9001, "error_message" to "appid invalid"), 400)
            }
        }
        assertEquals(listOf(CANDIDATE_APP_IDS[2]), GizwitsClient.probeAppIds("cn", baseUrl = server.url("/").toString()))
    }
}
