package com.kelofred.keloray.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.data.Device
import com.kelofred.keloray.data.Rule
import com.kelofred.keloray.data.SceneInfo
import com.kelofred.keloray.data.Settings
import com.kelofred.keloray.ui.screens.RuleEditor
import com.kelofred.keloray.ui.theme.KeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The schedule editor dialog on its own. (Runs at Robolectric's default screen
 * size: at phone-sized xxhdpi configs Robolectric never idles with a text field
 * inside a dialog window, independent of this app's code.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RuleEditorTest {
    @get:JUnitRule val compose = createAndroidComposeRule<ComponentActivity>()

    private var saved: Rule? = null

    private fun show(initial: Rule? = null) = compose.setContent {
        KeloTheme {
            RuleEditor(
                initial = initial,
                devices = listOf(Device("CORAL1", "pk", alias = "Coral tank"), Device("BULB1", "pk", alias = "Living room")),
                scenes = SceneInfo.local(),
                settings = Settings(homeLat = -33.92, homeLon = 18.42, homeUtc = 2.0),
                onDismiss = {},
                onSave = { saved = it },
            )
        }
    }

    @Test
    fun weekdaySunriseBecomesACronRule() {
        show()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Morning")
        // the dialog scrolls on Robolectric's small default screen
        compose.onNodeWithText("Sat").performScrollTo().performClick()
        compose.onNodeWithText("Sun").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        val r = saved!!
        assertEquals("Morning", r.name)
        assertEquals("CORAL1", r.did)
        assertEquals("sunrise", r.scene)
        assertNull(r.at)
        assertEquals(mapOf("hour" to 6, "minute" to 30, "day_of_week" to "mon,tue,wed,thu,fri"), r.cron)
    }

    @Test
    fun pickingASunTrackerPrefillsHomeLocation() {
        show()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Reef")
        compose.onNodeWithText("sunrise").performClick() // opens the scene menu
        compose.onAllNodesWithText("reef_day").onFirst().performClick()
        compose.waitForIdle()
        val bmp = captureAllWindows(compose.activity.window.decorView)
        java.io.File("build/screenshots").mkdirs()
        java.io.FileOutputStream("build/screenshots/08-schedule-editor.png").use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Save").performClick()
        val r = saved!!
        assertEquals("reef_day", r.scene)
        assertEquals("06:30", r.at) // every day -> daily "at"
        assertEquals(mapOf("latitude" to -33.92, "longitude" to 18.42, "utc_offset" to 2.0), r.params)
    }

    @Test
    fun editingKeepsTheExistingSchedule() {
        show(Rule("wake", "BULB1", "sunset", mapOf("seconds" to 600L), mapOf("hour" to 22, "minute" to 15, "day_of_week" to "sat,sun")))
        compose.onNodeWithText("Edit schedule").assertExists()
        compose.onNodeWithText("Time  22:15").assertExists()
        compose.onNodeWithText("Save").performClick()
        val r = saved!!
        assertEquals("BULB1", r.did)
        assertEquals("sat,sun", r.cron["day_of_week"])
        assertEquals(JsonUtil.stringify(mapOf("seconds" to 600L)), JsonUtil.stringify(r.params))
    }

    @Test
    fun badOptionsAreRejected() {
        show()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields.onFirst().performTextInput("X")
        fields[1].performTextInput("not json")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Options must be a JSON object").assertExists()
        assertNull(saved)
    }
}
