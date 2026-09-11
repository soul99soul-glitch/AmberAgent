package app.amber.feature.ui.pages.miniapp

import app.amber.agent.data.db.entity.MiniAppEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MiniAppRunnerLoadStateTest {
    @Test
    fun `repository failure becomes retryable error state`() = runTest {
        val state = loadMiniAppRunnerState {
            error("database unavailable")
        }

        assertEquals(MiniAppRunnerLoadState.Error("database unavailable"), state)
    }

    @Test
    fun `missing repository result becomes missing state`() = runTest {
        assertEquals(
            MiniAppRunnerLoadState.Missing,
            loadMiniAppRunnerState { null },
        )
    }

    @Test
    fun `loaded repository result becomes ready state`() = runTest {
        val app = MiniAppEntity(
            id = "runner-1",
            title = "Runner",
            description = "",
            htmlContent = "<p>runner</p>",
            createdAt = 1L,
            updatedAt = 1L,
        )

        assertEquals(MiniAppRunnerLoadState.Ready(app), loadMiniAppRunnerState { app })
    }

    @Test
    fun `cancellation is propagated to the coroutine scope`() = runTest {
        val cancellation = CancellationException("cancelled")
        val actual = try {
            loadMiniAppRunnerState { throw cancellation }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, actual)
    }
}
