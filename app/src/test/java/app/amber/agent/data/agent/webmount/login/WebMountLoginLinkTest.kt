package app.amber.feature.webmount.login

import android.app.Application
import android.content.Intent
import app.amber.core.utils.openUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountLoginLinkTest {
    @Test
    fun chatLoginLinkOpensTheInternalActivityForCustomSites() {
        val context = RuntimeEnvironment.getApplication()

        context.openUrl("amberagent://webmount/login?station=user_example")

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(InlineLoginActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals("user_example", intent.data?.getQueryParameter("station"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
