package app.amber.core.automation.display

import android.app.Application
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EmbeddedAdbDisplayBridgeTest {
    @Test
    fun probeRequiresActualBinderDeathAfterTransportClosure() = runBlocking {
        val binder = HelperBinder()
        var closed = false
        var launches = 0
        val bridge = EmbeddedAdbDisplayBridge {
            launches++
            RegisteredDisplayHelper(binder, Closeable { closed = true })
        }
        val reasons = mutableListOf<String>()

        bridge.start()
        val first = bridge.connect { reasons += it }.getOrThrow()
        assertEquals(2000, first.uid())
        assertTrue(bridge.ready.value)
        assertTrue(bridge.connect {}.exceptionOrNull() is DisplayHelperBusyException)
        first.disconnect()
        val second = bridge.connect { reasons += it }.getOrThrow()
        assertEquals(1, launches)
        assertTrue(second.interruptTransportForProbe())
        assertTrue(closed)
        assertEquals(true, second.probeTransportAlive())
        assertTrue(reasons.isEmpty())

        binder.reportDeath()
        assertFalse(bridge.ready.value)
        assertEquals(false, second.probeTransportAlive())
        assertEquals(listOf("display_helper_binder_died"), reasons)
    }

    @Test
    fun stopWhileAwaitingRegistrationDoesNotResurrectHelper() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var closed = false
        val bridge = EmbeddedAdbDisplayBridge {
            entered.complete(Unit)
            resume.await()
            RegisteredDisplayHelper(HelperBinder(), Closeable { closed = true })
        }
        val connecting = async { bridge.connect {} }
        entered.await()
        bridge.stop()
        resume.complete(Unit)
        assertTrue(connecting.await().isFailure)
        assertFalse(bridge.ready.value)
        assertTrue(closed)
    }

    @Test
    fun compositeFallsBackOnlyAtConnectionAndNeverOnBusyHelper() = runBlocking {
        val embedded = EmbeddedAdbDisplayBridge { RegisteredDisplayHelper(HelperBinder(), Closeable {}) }
        val unavailable = object : DisplayBridge {
            var calls = 0
            override suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge> {
                calls++
                return Result.failure(IllegalStateException("unavailable"))
            }
        }
        val first = CompositeDisplayBridge(embedded, unavailable)
        val conn = first.connect {}.getOrThrow()
        assertTrue(first.connect {}.exceptionOrNull() is DisplayHelperBusyException)
        assertEquals(0, unavailable.calls)
        conn.disconnect()
        val reasons = mutableListOf<String>()
        CompositeDisplayBridge(unavailable, embedded).connect { reasons += it }.getOrThrow()
        embedded.stop()
        assertEquals(1, unavailable.calls)
        assertEquals(listOf("display_helper_stopped"), reasons)
    }

    private class HelperBinder(
        local: Binder = Binder().apply {
            attachInterface(object : IAmberDisplayService.Default() {
                override fun uid() = 2000
            }, IAmberDisplayService.DESCRIPTOR)
        },
    ) : IBinder by local {
        private var recipient: IBinder.DeathRecipient? = null
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            this.recipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (this.recipient !== recipient) return false
            this.recipient = null
            return true
        }

        fun reportDeath() { recipient?.binderDied() }
    }
}
