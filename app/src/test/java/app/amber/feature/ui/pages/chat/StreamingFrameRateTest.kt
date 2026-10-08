package app.amber.feature.ui.pages.chat

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamingFrameRateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun adaptiveDisplayKeepsSystemRefreshPreferenceDuringStreaming() {
        var active by mutableStateOf(false)
        var present by mutableStateOf(true)
        lateinit var activity: Activity
        compose.setContent {
            activity = LocalContext.current as Activity
            if (present) PreferStreamingFrameRate(active, adaptiveRefreshRate = true)
        }
        compose.runOnIdle {
            activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 90f }
            active = true
        }
        compose.runOnIdle { assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f) }
        compose.runOnIdle { active = false }
        compose.runOnIdle { assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f) }
        compose.runOnIdle { active = true }
        compose.runOnIdle { present = false }
        compose.runOnIdle { assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f) }
    }

    @Test
    fun streamRequests120HzAndRestoresTheOriginalPreferenceOnCompletionAndLeaving() {
        var active by mutableStateOf(false)
        var present by mutableStateOf(true)
        lateinit var activity: Activity
        compose.setContent {
            activity = LocalContext.current as Activity
            if (present) PreferStreamingFrameRate(active)
        }
        compose.runOnIdle {
            activity.window.attributes = activity.window.attributes.apply { preferredRefreshRate = 90f }
            active = true
        }
        compose.runOnIdle { assertEquals(120f, activity.window.attributes.preferredRefreshRate, 0f) }
        compose.runOnIdle { active = false }
        compose.runOnIdle { assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f) }
        compose.runOnIdle { active = true }
        compose.runOnIdle { assertEquals(120f, activity.window.attributes.preferredRefreshRate, 0f) }
        compose.runOnIdle { present = false }
        compose.runOnIdle { assertEquals(90f, activity.window.attributes.preferredRefreshRate, 0f) }
    }
}
