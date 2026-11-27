package app.amber.core.settings

import app.amber.core.agent.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTextWeightSerializationTest {
    @Test
    fun `legacy display preferences default to system weight`() {
        val legacy = JsonInstant.decodeFromString<DisplaySetting>(
            """{"fontSizeRatio":1.25,"chatFontFamily":"serif"}""",
        )

        assertEquals(TextWeight.SYSTEM, legacy.textWeight)
        assertEquals(1.25f, legacy.fontSizeRatio)
        assertEquals(ChatFontFamily.SERIF, legacy.chatFontFamily)

        val updated = legacy.copy(textWeight = TextWeight.HEAVIER)
        assertEquals(updated, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(updated)))
    }
}
