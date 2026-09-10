package app.amber.feature.ui.pages.search

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestSearchRequestRunnerTest {

    @Test
    fun `submitting a new query cancels the pending debounce`() = runTest {
        val runner = LatestSearchRequestRunner(this)
        val committed = mutableListOf<String>()

        runner.submit(delayMillis = 300) { request ->
            if (runner.isCurrent(request)) committed += "old"
        }
        advanceTimeBy(100)
        runner.submit(delayMillis = 0) { request ->
            if (runner.isCurrent(request)) committed += "new"
        }

        advanceUntilIdle()

        assertEquals(listOf("new"), committed)
    }

    @Test
    fun `a non cooperative old query cannot commit after a newer query`() = runTest {
        val runner = LatestSearchRequestRunner(this)
        val committed = mutableListOf<String>()

        runner.submit(delayMillis = 0) { request ->
            // Simulate a repository implementation that ignores cancellation
            // until its work has returned.
            withContext(NonCancellable) {
                kotlinx.coroutines.delay(100)
            }
            if (runner.isCurrent(request)) committed += "old"
        }
        advanceTimeBy(10)
        runner.submit(delayMillis = 0) { request ->
            if (runner.isCurrent(request)) committed += "new"
        }

        advanceUntilIdle()

        assertEquals(listOf("new"), committed)
    }
}
