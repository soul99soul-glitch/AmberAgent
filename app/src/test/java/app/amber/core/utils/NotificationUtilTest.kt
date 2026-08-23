package app.amber.core.utils

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class NotificationUtilTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun preAndroid13DoesNotRequirePostNotificationsRuntimePermission() {
        val channelId = "enabled-generation"
        createChannel(channelId, NotificationManager.IMPORTANCE_LOW)

        assertTrue(NotificationUtil.hasNotificationPermission(context))
        assertTrue(NotificationUtil.canShowNotification(context, channelId))
    }

    @Test
    fun missingForegroundChannelFailsClosedOnAndroid26AndNewer() {
        assertFalse(NotificationUtil.canShowNotification(context, "not-created-yet"))
    }

    @Test
    fun blockedForegroundChannelFailsVisibilityPreflight() {
        val channelId = "blocked-generation"
        createChannel(channelId, NotificationManager.IMPORTANCE_NONE)

        assertFalse(NotificationUtil.canShowNotification(context, channelId))
    }

    @Test
    fun appLevelNotificationBlockFailsVisibilityPreflightBeforeAndroid13() {
        val channelId = "app-blocked-generation"
        createChannel(channelId, NotificationManager.IMPORTANCE_LOW)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)

        assertFalse(NotificationUtil.canShowNotification(context, channelId))
    }

    @Test
    @Config(sdk = [33])
    fun android13RuntimePermissionIsRequiredEvenWhenChannelIsEnabled() {
        val channelId = "permission-generation"
        createChannel(channelId, NotificationManager.IMPORTANCE_LOW)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(NotificationUtil.hasNotificationPermission(context))
        assertFalse(NotificationUtil.canShowNotification(context, channelId))

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationUtil.canShowNotification(context, channelId))
    }

    private fun createChannel(channelId: String, importance: Int) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, channelId, importance),
        )
    }
}
