package app.amber.feature.webmount.primitives

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebMountSessionOwnerDispatchTest {
    @Test
    fun `human lease guards synchronous dispatch until release`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val pool = WebViewPool(context)
            val owner = WebMountSessionOwner(context, pool)
            val result = owner.acquire(
                sessionId = "wm_human_dispatch_test",
                actor = WebMountOwner.HUMAN,
            )
            val lease = (result as WebMountLeaseResult.Granted).lease
            try {
                var dispatched = false
                assertTrue(
                    owner.dispatchIfHumanActive(
                        leaseId = lease.leaseId,
                        action = { dispatched = true },
                    ),
                )
                assertTrue(dispatched)

                owner.release(lease.leaseId, "test release")
                assertFalse(
                    owner.dispatchIfHumanActive(
                        leaseId = lease.leaseId,
                        action = {},
                    ),
                )
            } finally {
                pool.destroyAll("test cleanup")
                Dispatchers.resetMain()
            }
        }
}
