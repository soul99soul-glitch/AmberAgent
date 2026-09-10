package app.amber.feature.tools

import android.app.Application
import app.amber.agent.RouteActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DynamicShortcutIntentTest {
    @Test
    fun publishedNewChatIntentResolvesToRouteActivity() {
        val context = RuntimeEnvironment.getApplication()
        val intent = DynamicShortcutPublisher.newChatShortcut(context).intent
        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        val resolved = context.packageManager.resolveActivity(intent, 0)
        assertNotNull("Published shortcut must resolve against the app manifest", resolved)
        assertEquals(RouteActivity::class.java.name, resolved!!.activityInfo.name)
        assertEquals(DynamicShortcutPublisher.Route.NewChat, DynamicShortcutPublisher.screenFromIntent(intent))
    }
}
