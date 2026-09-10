package app.amber.feature.reminder

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.RouteActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReminderNotificationIntentTest {
    @Test
    fun `notification opens existing chat prefill route`() {
        val reminder = ReminderSnapshot(
            id = "reminder-1",
            title = "喝水",
            message = "现在喝水",
            triggerAtEpochMs = 1L,
        )

        val intent = ReminderReceiver.reminderOpenIntent(
            ApplicationProvider.getApplicationContext(),
            reminder,
        )

        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals("提醒：喝水\n现在喝水", intent.getStringExtra(RouteActivity.EXTRA_OPEN_CHAT_PROMPT))
        assertNull(intent.data)
    }
}
