package app.amber.feature.ui.pages.chat

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineFrameSignalTest {
    @Test
    fun waitsForADrawNewerThanTheCycleBaseline() = runTest {
        val signal = TimelineFrameSignal()
        signal.signal() // previous mount/cycle
        val baseline = signal.snapshot()

        var released = false
        launch {
            signal.awaitAfter(baseline)
            released = true
        }
        runCurrent()
        assertFalse(released)

        signal.signal()
        runCurrent()
        assertTrue(released)
    }

    @Test
    fun aBaselineTakenAfterTheDrawDoesNotReleaseUntilAnotherDraw() = runTest {
        val signal = TimelineFrameSignal()
        signal.signal()
        val baseline = signal.snapshot()

        var released = false
        launch {
            signal.awaitAfter(baseline)
            released = true
        }
        runCurrent()
        assertFalse(released)

        signal.signal()
        runCurrent()
        assertTrue(released)
    }
}
