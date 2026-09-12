package app.amber.feature.webmount.core

import android.app.Application
import app.amber.core.infra.AppScope
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.cookie.EndpointSpec
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountManagerTest {
    @Test
    fun cancellingAProbePreservesThePreviousPersistedStationState() = runBlocking {
        var waitForResponse = false
        val adapter = object : WebMountAdapter {
            override val id = "test"
            override val displayName = "Test"
            override val authMethods = setOf(WebMountAuthMethod.ANONYMOUS)
            override val capabilityHints = setOf(WebMountCapability.READ_ONLY)
            override val endpoints = emptyList<EndpointSpec>()
            override val toolNamePrefix = "test_"
            override suspend fun probe(): WebMountProbeResult = runCatching {
                if (waitForResponse) awaitCancellation()
                WebMountProbeResult.success(WebMountCapability.READ_ONLY, "reachable")
            }.getOrElse { WebMountProbeResult.failed(it.message.orEmpty(), it) }
        }
        val appScope = AppScope()
        fun newManager() = WebMountManager(
            context = RuntimeEnvironment.getApplication(),
            adapters = listOf(adapter),
            cookieProvider = WebMountCookieProvider(),
            activityStore = AgentToolActivityStore(),
            appScope = appScope,
        )
        try {
            val manager = newManager()
            val previous = manager.probe(adapter.id)
            waitForResponse = true
            val probe = launch(start = CoroutineStart.UNDISPATCHED) { manager.probe(adapter.id) }
            assertEquals(WebMountStatus.PROBING, manager.states.value[adapter.id]?.status)

            probe.cancelAndJoin()

            assertEquals(previous, manager.states.value[adapter.id])
            assertEquals(previous, newManager().states.value[adapter.id])
        } finally {
            appScope.cancel()
        }
    }
}
