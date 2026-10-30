package app.amber.feature.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBubbleWidthTest {
    @Test
    fun capsToTheSameFractionTheOldBoxWithConstraintsUsed() {
        assertEquals(344, cappedBubbleWidth(parentWidth = 400, fraction = 0.86f))
        assertEquals(368, cappedBubbleWidth(parentWidth = 400, fraction = 0.92f))
    }

    @Test
    fun roundsToTheNearestPixelAndClampsToTheParent() {
        assertEquals(87, cappedBubbleWidth(parentWidth = 100, fraction = 0.866f))
        assertEquals(100, cappedBubbleWidth(parentWidth = 100, fraction = 1f))
        assertEquals(0, cappedBubbleWidth(parentWidth = 100, fraction = 0f))
    }
}
