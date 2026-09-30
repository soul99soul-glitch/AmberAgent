package app.amber.feature.ui.theme

import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.DisplaySetting
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemePackTransferTest {
    private fun fixture() = ThemePackTransfer.decode(
        File("../test-fixtures/themes/cross-platform-v1.json").readText(),
    )

    @Test
    fun `JSON string design from provider is decoded and validated before preview`() {
        val original = fixture()
        val arguments = ThemePackTransfer.toolPayload(original).toMutableMap()
        arguments["design"] = JsonPrimitive(
            JsonInstant.encodeToString(ThemeDesign.serializer(), requireNotNull(original.design)),
        )
        assertEquals(original, ThemePackTransfer.toolRecipe(JsonObject(arguments)))

        val patched = ThemePackTransfer.toolRecipe(buildJsonObject {
            put("base_id", original.id)
            put("design", """{"components":{"cardRadius":12}}""")
        }, original)
        assertEquals(12.0, patched.design?.components?.cardRadius)
        assertEquals(original.design?.light, patched.design?.light)
        assertEquals(original.design?.patterns, patched.design?.patterns)

        val invalid = runCatching {
            ThemePackTransfer.toolRecipe(buildJsonObject {
                put("base_id", original.id)
                put("design", """{"components":{"cardRadius":300}}""")
            }, original)
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
    }

    @Test
    fun `new core palette fills portable style slots when provider omits them`() {
        val original = fixture()
        val arguments = ThemePackTransfer.toolPayload(original).toMutableMap().apply {
            remove("brand_mark")
            remove("shortcut_icon_style")
            remove("chrome_typeface")
        }
        val document = ThemePackTransfer.toolRecipe(JsonObject(arguments))
        assertEquals("systemWordmark", document.brandMark)
        assertEquals("systemOutline", document.shortcutIconStyle)
        assertEquals("system", document.chromeTypeface)
        assertEquals(original.design, document.design)
        assertEquals(document, ThemePackTransfer.decode(ThemePackTransfer.encode(document)))
    }

    @Test
    fun `legacy opaque ARGB exports exactly and transparent color is never silently replaced`() {
        assertEquals("#FF0000", ThemePackTransfer.export(DisplaySetting(accentColor = "#FFFF0000")).accentHex)
        val failure = runCatching {
            ThemePackTransfer.export(DisplaySetting(accentColor = "#80FF0000"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
