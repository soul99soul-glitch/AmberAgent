package app.amber.core.settings

import app.amber.core.agent.utils.JsonInstant
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiSettingSerializationTest {
    @Test
    fun `legacy allowModelJavaScript field is ignored`() {
        val setting = JsonInstant.decodeFromString<GenerativeUiSetting>(
            """
            {
              "enabled": true,
              "allowModelJavaScript": true,
              "maxWidgetCodeChars": 8000
            }
            """.trimIndent()
        )

        assertTrue(setting.enabled)
        assertEquals(8000, setting.maxWidgetCodeChars)
    }
}
