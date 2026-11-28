package app.amber.core.automation.display

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayHelperDeviceTest {
    @Test
    fun shellHelperReturnsBinderCreatesDisplayAndDies() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val displays = context.getSystemService(DisplayManager::class.java)
        val registration = DisplayHelperBinderProvider.expect()
        val command = "/system/bin/env CLASSPATH=${context.applicationInfo.sourceDir} /system/bin/app_process " +
            "/system/bin --nice-name=amber_display_smoke app.amber.core.automation.display.AmberDisplayHelperMain"
        val pipes = instrumentation.uiAutomation.executeShellCommandRw(command)
        val stdin = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
        try {
            stdin.write((registration.token + "\n").toByteArray(Charsets.US_ASCII))
            stdin.flush()
            val binder = withTimeout(10_000) { registration.awaitBinder() }
            val died = CompletableDeferred<Unit>()
            binder.linkToDeath(IBinder.DeathRecipient { died.complete(Unit) }, 0)
            val service = IAmberDisplayService.Stub.asInterface(binder)
            try {
                assertEquals(2000, service.uid())
                val caps = service.capabilities()
                assertEquals(2000, caps.getInt(AmberDisplayUserService.CAP_UID))
                assertTrue(caps.getBoolean(AmberDisplayUserService.CAP_OWN_FOCUS))
                assertTrue(caps.getBoolean(AmberDisplayUserService.CAP_DESTROY_CONTENT_ON_REMOVAL))
                assertTrue(caps.getBoolean(AmberDisplayUserService.CAP_SECONDARY_ACTIVITIES))
                ImageReader.newInstance(720, 1600, PixelFormat.RGBA_8888, 2).use { reader ->
                    val id = service.createDisplay("device-smoke", context.packageName, 1L, 720, 1600, 320, reader.surface)
                    assertTrue("createDisplay returned $id", id > 0)
                    assertNotNull(displays.getDisplay(id))
                    service.release(1L, "device-smoke-complete")
                    withTimeout(5_000) { while (displays.getDisplay(id) != null) delay(50) }
                    Log.i("DisplayHelperDeviceTest", "uid=2000 capabilities=$caps displayId=$id released=true")
                }
            } finally {
                stdin.close()
            }
            withTimeout(5_000) { died.await() }
            Log.i("DisplayHelperDeviceTest", "binderDeathObserved=true")
        } finally {
            registration.close()
            stdin.close()
            pipes[0].close()
        }
    }
}
