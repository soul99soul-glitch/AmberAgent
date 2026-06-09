package app.amber.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBoundsTest {
    @Test
    fun pointInsideDisplayIsAccepted() {
        val point = requirePointInDisplayBounds(10f, 20f, width = 100, height = 200, label = "tap")

        assertEquals(10f, point.x)
        assertEquals(20f, point.y)
    }

    @Test
    fun pointOutsideDisplayIsRejected() {
        val error = runCatching {
            requirePointInDisplayBounds(1000f, 20f, width = 100, height = 200, label = "tap")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("out of bounds"))
    }
}
