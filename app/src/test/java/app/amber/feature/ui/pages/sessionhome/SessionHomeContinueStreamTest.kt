package app.amber.feature.ui.pages.sessionhome

import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueRoute
import app.amber.feature.home.ContinueSourceKind
import app.amber.feature.home.ContinueStatus
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHomeContinueStreamTest {
    @Test
    fun `continue source error keeps cards and reports independently`() = runTest {
        val candidate = candidate()
        var error: Throwable? = null
        val state = observeContinueStream(
            source = {
                flow {
                    emit(listOf(candidate))
                    throw IllegalStateException("continue database unavailable")
                }
            },
            onValue = {},
            onError = { error = it },
        ).stateIn(this, SharingStarted.Eagerly, emptyList())

        advanceUntilIdle()

        assertEquals(listOf(candidate), state.value)
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `continue source construction error does not replace previous cards`() = runTest {
        var error: Throwable? = null
        val previous = candidate()
        val state = observeContinueStream(
            source = { throw IllegalStateException("source unavailable") },
            onValue = {},
            onError = { error = it },
        ).stateIn(this, SharingStarted.Eagerly, listOf(previous))

        advanceUntilIdle()

        assertTrue(error is IllegalStateException)
        assertEquals(listOf(previous), state.value)
    }

    private fun candidate() = ContinueCandidate(
        sourceKind = ContinueSourceKind.DEEP_READ,
        sourceId = "topic-1",
        route = ContinueRoute.DeepRead("topic-1", "Topic"),
        title = "Topic",
        summary = "partial",
        lastUpdatedAt = Instant.EPOCH,
        status = ContinueStatus.FAILED_RESUMABLE,
    )
}
