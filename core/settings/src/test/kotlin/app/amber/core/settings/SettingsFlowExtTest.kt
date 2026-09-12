package app.amber.core.settings

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFlowExtTest {

    @Test
    fun `shared raw flow decodes once for state and aggregator collectors`() = runBlocking {
        val subscriptions = AtomicInteger()
        val source = flow {
            subscriptions.incrementAndGet()
            emit(7)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val shared = source.shareSettingsRawFlow(scope)
        val firstValue = withTimeout(5_000) { shared.first() }
        val secondValue = withTimeout(5_000) { shared.first() }

        assertEquals(7, firstValue)
        assertEquals(7, secondValue)
        assertEquals(1, subscriptions.get())

        scope.cancel()
    }
}
