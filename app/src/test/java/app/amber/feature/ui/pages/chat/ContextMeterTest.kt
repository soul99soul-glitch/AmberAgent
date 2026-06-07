package app.amber.feature.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Graphite context-meter fill math (design §6.2 "5 mono bars"). */
class ContextMeterTest {
    @Test fun emptyUsage_zeroBars() {
        assertEquals(0, contextMeterFilledBars(used = 0, total = 100))
    }

    @Test fun zeroTotal_guardsAgainstDivByZero() {
        assertEquals(0, contextMeterFilledBars(used = 50, total = 0))
    }

    @Test fun lowUsage_roundsToOneBar() {
        // 0.10 * 5 + 0.5 = 1.0 -> 1
        assertEquals(1, contextMeterFilledBars(used = 10, total = 100))
    }

    @Test fun halfUsage_threeBars() {
        // 0.50 * 5 + 0.5 = 3.0 -> 3
        assertEquals(3, contextMeterFilledBars(used = 50, total = 100))
    }

    @Test fun fullUsage_fiveBars() {
        assertEquals(5, contextMeterFilledBars(used = 100, total = 100))
    }

    @Test fun overflow_clampsToFive() {
        assertEquals(5, contextMeterFilledBars(used = 200, total = 100))
    }
}
