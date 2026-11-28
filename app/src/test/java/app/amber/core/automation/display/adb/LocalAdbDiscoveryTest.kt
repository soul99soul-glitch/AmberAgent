package app.amber.core.automation.display.adb

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [LocalAdbDiscoveryTest.ObservedNsd::class])
class LocalAdbDiscoveryTest {
    @Test
    fun tracksOnlyLiveLocalEndpointsAndReleasesListenersOnCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val nsd = Shadow.newInstanceOf(NsdManager::class.java)
        val shadow: ObservedNsd = Shadow.extract(nsd)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == NSD_SERVICE) nsd else super.getSystemService(name)
        }
        val seen = mutableListOf<InetSocketAddress?>()
        val collector = launch { LocalAdbTlsClient(context).pairingEndpoints().toList(seen) }
        try {
            runCurrent()
            assertEquals(listOf(null), seen)
            val service = NsdServiceInfo().apply {
                serviceName = "test-device"
                port = 35351
                hostAddresses = listOf(InetAddress.getByName("192.0.2.7"))
            }
            shadow.discovery.onServiceFound(service)
            shadow.discovery.onServiceFound(service)
            val old = shadow.registered.single()
            old.onServiceUpdated(service)
            runCurrent()
            assertEquals(listOf(null), seen)

            val loopback = InetAddress.getByName("127.0.0.1")
            service.hostAddresses = listOf(loopback)
            old.onServiceUpdated(service)
            old.onServiceUpdated(service)
            runCurrent()
            assertEquals(listOf(null, InetSocketAddress(loopback, 35351)), seen)
            shadow.discovery.onServiceLost(service)
            runCurrent()
            assertEquals(null, seen.last())
            assertEquals(listOf(old), shadow.unregistered)

            shadow.discovery.onServiceFound(service)
            val current = shadow.registered.last()
            old.onServiceUpdated(service)
            service.port = 35352
            current.onServiceUpdated(service)
            runCurrent()
            assertEquals(listOf(null, InetSocketAddress(loopback, 35351), null, InetSocketAddress(loopback, 35352)), seen)
            val newer = NsdServiceInfo().apply {
                serviceName = "new-pairing-dialog"
                hostAddresses = listOf(loopback)
                port = 35353
            }
            shadow.discovery.onServiceFound(newer)
            shadow.registered.last().onServiceUpdated(newer)
            current.onServiceUpdated(service)
            runCurrent()
            assertEquals(InetSocketAddress(loopback, 35353), seen.last())
            collector.cancelAndJoin()
            assertTrue(shadow.stopped)
            assertEquals(shadow.registered, shadow.unregistered)
        } finally {
            collector.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Implements(NsdManager::class)
    class ObservedNsd {
        lateinit var discovery: NsdManager.DiscoveryListener
        val registered = mutableListOf<NsdManager.ServiceInfoCallback>()
        val unregistered = mutableListOf<NsdManager.ServiceInfoCallback>()
        var stopped = false

        @Implementation
        fun discoverServices(type: String, protocol: Int, network: Network?, executor: Executor, listener: NsdManager.DiscoveryListener) {
            discovery = listener
        }

        @Implementation
        fun registerServiceInfoCallback(info: NsdServiceInfo, executor: Executor, callback: NsdManager.ServiceInfoCallback) {
            registered += callback
        }

        @Implementation
        fun unregisterServiceInfoCallback(callback: NsdManager.ServiceInfoCallback) { unregistered += callback }

        @Implementation
        fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) { stopped = listener === discovery }
    }
}
