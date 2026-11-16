package com.kelofred.keloray.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kelofred.keloray.App
import com.kelofred.keloray.KeloApp
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.ui.screens.ChannelMapScreen
import com.kelofred.keloray.ui.screens.DiscoverScreen
import com.kelofred.keloray.ui.theme.KeloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the real Compose UI against a fake keloray-control server: checks the
 * screens render, that taps reach the API with the right payloads, and saves
 * screenshots to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val fake = FakeKelorayServer()
    private lateinit var app: KeloApp
    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication() as KeloApp
        val url = fake.start()
        app.graph.settings.update { it.copy(mode = Mode.SERVER, serverUrl = url, homeLat = -33.92, homeLon = 18.42, homeUtc = 2.0) }
        vm = AppViewModel(app)
    }

    @After
    fun tearDown() = fake.shutdown()

    private fun shot(name: String) {
        compose.waitForIdle()
        val bmp = captureAllWindows(compose.activity.window.decorView)
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForRequest(line: String): Any? {
        compose.waitUntil(15_000) { fake.requests.any { it.first == line } }
        return fake.requests.last { it.first == line }.second
    }

    private fun setApp() = compose.setContent { KeloTheme { App(vm) } }

    @Test
    fun setupScreen() {
        app.graph.settings.update { it.copy(mode = null) }
        setApp()
        waitForText("Gizwits cloud")
        shot("01-setup-cloud")
        compose.onNodeWithText("My server").performClick()
        waitForText("Server address")
        shot("02-setup-server")
    }

    @Test
    fun devicesListAndPower() {
        setApp()
        waitForText("Coral tank")
        waitForText("Study lamp")
        shot("03-devices")
        compose.onAllNodesWithText("On")[0].performClick()
        assertEquals(mapOf("on" to true), waitForRequest("POST /api/devices/CORAL1/power"))
    }

    @Test
    fun coralDeviceSpectrumAndScene() {
        setApp()
        waitForText("Coral tank")
        compose.onNodeWithText("Coral tank").performClick()
        waitForText("Coral spectrum")
        waitForText("A scene is running on the server")
        shot("04-coral-device")

        compose.onNodeWithText("Actinic").performScrollTo().performClick()
        val body = waitForRequest("POST /api/devices/CORAL1/control") as Map<*, *>
        val attrs = body["attrs"] as Map<*, *>
        assertEquals(1L, attrs["onOff"])
        assertEquals(70L, (attrs["channels"] as Map<*, *>)["rb"])

        compose.onNodeWithText("reef_day").performScrollTo().performClick()
        waitForText("Tropical reef sun")
        shot("05-scene-sheet")
        compose.onNodeWithText("Run").performScrollTo().performClick()
        val scene = waitForRequest("POST /api/devices/CORAL1/scene") as Map<*, *>
        assertEquals("reef_day", scene["scene"])
        val params = scene["params"] as Map<*, *>
        assertEquals(-33.92, params["latitude"]) // prefilled from the home location
        assertEquals(60L, params["tick"])
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun coralDeviceDarkTheme() {
        setApp()
        waitForText("Coral tank")
        compose.onNodeWithText("Coral tank").performClick()
        waitForText("Coral spectrum")
        shot("12-coral-device-dark")
    }

    @Test
    fun bulbDeviceControls() {
        setApp()
        waitForText("Living room")
        compose.onNodeWithText("Living room").performClick()
        waitForText("White light")
        waitForText("Currently on")
        shot("06-bulb-device")
    }

    @Test
    fun schedulesListAndToggle() {
        setApp()
        waitForText("Coral tank")
        compose.onNodeWithContentDescription("Schedules").performClick()
        waitForText("weekday wake-up")
        waitForText("Weekdays")
        shot("07-schedules")
        compose.onAllNodes(isToggleable())[0].performClick()
        val rule = waitForRequest("POST /api/schedules") as Map<*, *>
        assertEquals("weekday wake-up", rule["name"])
        assertEquals(false, rule["enabled"])
    }

    @Test
    fun settingsScreen() {
        setApp()
        waitForText("Coral tank")
        compose.onNodeWithContentDescription("Settings").performClick()
        waitForText("Home location")
        shot("09-settings")
    }

    @Test
    fun discoverScreen() {
        compose.setContent { KeloTheme { DiscoverScreen(vm, "CORAL1") {} } }
        waitForText("9 datapoints")
        shot("10-discover")
    }

    @Test
    fun channelMapAutoDetectAndSave() {
        val snackbar = SnackbarHostState()
        compose.setContent { KeloTheme { ChannelMapScreen(vm, "CORAL1", snackbar) {} } }
        compose.waitUntil(15_000) { vm.discovery.value["CORAL1"] is Load.Ok }
        compose.onNodeWithText("Auto-detect").performClick()
        waitForText("Cold_white  0..1000")
        shot("11-channel-map")
        compose.onNodeWithText("Save channel map").performScrollTo().performClick()
        compose.waitForIdle()
        val map = app.graph.channelMaps.get("CORAL1")
        assertTrue(map.configured())
        assertEquals("Royal_blue", map.specs.getValue("rb").name)
        assertEquals(1000.0, map.specs.getValue("rb").hi, 0.0)
        assertEquals("UV2", map.specs.getValue("uv2").name)
        assertEquals(255.0, map.specs.getValue("uv2").hi, 0.0)
        assertEquals(mapOf("Royal_blue" to 500), map.toAttrs(mapOf("rb" to 50)))
    }
}
