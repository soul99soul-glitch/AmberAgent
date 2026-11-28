package app.amber.core.automation.display

import android.app.Application
import android.os.Binder
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DisplayHelperBinderProviderTest {
    @Test
    fun onlyShellCanRegisterOnceWithTheCurrentToken() = runBlocking {
        val provider = DisplayHelperBinderProvider()
        val binder = Binder()
        val extras = Bundle().apply { putBinder(DisplayHelperBinderProvider.HELPER_BINDER, binder) }
        DisplayHelperBinderProvider.expect().use { pending ->
            for (uid in listOf(0, 1000, 10231)) {
                ShadowBinder.setCallingUid(uid)
                assertThrows(SecurityException::class.java) {
                    provider.call("register_helper", pending.token, extras)
                }
            }
            ShadowBinder.setCallingUid(2000)
            assertThrows(SecurityException::class.java) { provider.call("register_helper", "0".repeat(64), extras) }
            assertThrows(IllegalArgumentException::class.java) { provider.call("unknown", pending.token, extras) }
            val reply = provider.call("register_helper", pending.token, extras)
            assertNotNull(reply.getBinder(DisplayHelperBinderProvider.HOST_BINDER))
            assertSame(binder, pending.awaitBinder())
            assertThrows(SecurityException::class.java) { provider.call("register_helper", pending.token, extras) }
        }
        Unit
    }

    @Test
    fun canceledLaunchCannotRegisterAgainstTheNextLaunch() {
        ShadowBinder.setCallingUid(2000)
        val previous = DisplayHelperBinderProvider.expect().use { it.token }
        val provider = DisplayHelperBinderProvider()
        val extras = Bundle().apply { putBinder(DisplayHelperBinderProvider.HELPER_BINDER, Binder()) }
        DisplayHelperBinderProvider.expect().use { next ->
            assertNotEquals(previous, next.token)
            assertThrows(SecurityException::class.java) { provider.call("register_helper", previous, extras) }
        }
    }
}
