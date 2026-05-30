package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingCodeBlockTest {
    @Test
    fun `streaming code block stays expanded while content is arriving`() {
        val code = (1..12).joinToString("\n") { "line $it" }

        assertEquals(
            code,
            displayCodeBlockContent(
                code = code,
                isExpanded = false,
                activeStreamingBlock = true,
            ),
        )
    }

    @Test
    fun `settled collapsed code block uses normal ten line limit`() {
        val code = (1..12).joinToString("\n") { "line $it" }
        val expected = (1..10).joinToString("\n") { "line $it" }

        assertEquals(
            expected,
            displayCodeBlockContent(
                code = code,
                isExpanded = false,
                activeStreamingBlock = false,
            ),
        )
    }
}
