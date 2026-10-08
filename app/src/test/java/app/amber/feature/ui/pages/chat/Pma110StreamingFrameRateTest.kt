package app.amber.feature.ui.pages.chat

import android.app.Application
import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Pma110StreamingFrameRateTest {
    @Test
    fun privateProtocolIsEnabledOnlyForTheVerifiedFirmware() {
        assertTrue(supportsPma110StreamingRate("PMA110", "PMA110_17.0.0.104(CN01)"))
        assertFalse(supportsPma110StreamingRate("PMA110", "PMA110_17.0.0.105(CN01)"))
        assertFalse(supportsPma110StreamingRate("CPH2749", "PMA110_17.0.0.104(CN01)"))
    }

    @Test
    fun completionBackgroundAndReentryRemoveAndRestoreOnlyTheOwningPackageVote() {
        val rates = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(12, code)
                data.enforceInterface("com.oplus.screenmode.IOplusScreenMode")
                assertEquals("app.amber.agent", data.readString())
                rates += data.readInt()
                assertEquals(0, data.dataAvail())
                reply!!.writeNoException()
                reply.writeBoolean(true)
                return true
            }
        }
        val controller = Pma110StreamingRate(binder, "app.amber.agent")
        controller.release() // Clear a prior process's stale vote.
        controller.update(true)
        controller.update(true)
        controller.update(false) // Pause/background or finish generation.
        controller.update(true)
        controller.release() // Leave the chat screen.
        assertEquals(listOf(0, 3, 0, 3, 0), rates)
    }

    @Test
    fun rejectedAcquisitionStillAttemptsCleanupWithoutCrashing() {
        val rates = mutableListOf<Int>()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.oplus.screenmode.IOplusScreenMode")
                data.readString()
                rates += data.readInt()
                throw SecurityException("Vendor denied the call")
            }
        }
        val controller = Pma110StreamingRate(binder, "app.amber.agent")
        controller.update(true)
        controller.release()
        assertEquals(listOf(3, 0), rates)
    }
}
