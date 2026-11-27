package app.amber.feature.ui.theme

import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.TextWeight
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `Pi and Notion presets match the iOS recipes and pass validation`() {
        val pi = requireNotNull(ThemePackTransfer.builtin("pi-steel"))
        assertEquals("点阵 · Pi", pi.displayName)
        assertEquals("pi", pi.paper)
        assertEquals("#6B8CAD", pi.accentHex)
        assertEquals("lineGrid", pi.canvasStyle)
        assertEquals("serifWordmark", pi.brandMark)
        assertEquals("monospace", pi.chromeTypeface)

        val notion = requireNotNull(ThemePackTransfer.builtin("notion-blue"))
        assertEquals("Notion · 暖白", notion.displayName)
        assertEquals("notion", notion.paper)
        assertEquals("#0075DE", notion.accentHex)
        assertEquals("flat", notion.canvasStyle)

        ThemePackTransfer.builtinPackIds.forEach { id ->
            val document = requireNotNull(ThemePackTransfer.builtin(id))
            assertEquals(id, document.id)
            assertEquals(emptyList<String>(), ThemePackTransfer.validationIssues(document))
        }
    }

    @Test
    fun `Arquiel presets retain their portable identity and preserve display preferences`() {
        val ids = listOf(
            "arquiel-warm-editorial", "arquiel-things-polish", "arquiel-comet-indigo",
            "arquiel-takram-moss", "arquiel-bookcraft-cinnabar", "arquiel-linear-terminal",
        )
        assertEquals(ids, ThemePackTransfer.builtinPackIds.takeLast(ids.size))
        assertTrue(ThemePackTransfer.builtinIds.containsAll(ids))
        val display = DisplaySetting(textWeight = TextWeight.entries.last(), chatFontFamily = ChatFontFamily.entries.last())
        ids.forEach { id ->
            val recipe = requireNotNull(ThemePackTransfer.builtin(id))
            val applied = ThemePackageApplier.applyTokens(ThemePackTransfer.asPackage(recipe), display)
            assertEquals(display, applied.copy(accentColor = display.accentColor, themePack = null))
            assertEquals(recipe, ThemePackTransfer.decode(ThemePackTransfer.encode(ThemePackTransfer.export(applied))))
            assertNull(recipe.canvasScope)
            assertNull(recipe.settingsChrome)
        }
        val cinnabar = requireNotNull(ThemePackTransfer.builtin("arquiel-bookcraft-cinnabar"))
        assertEquals("serif", cinnabar.chromeTypeface)
        assertEquals("#F6F1E7", cinnabar.design?.light?.background)
        assertEquals("#151210", cinnabar.design?.dark?.background)
        val linear = requireNotNull(ThemePackTransfer.builtin("arquiel-linear-terminal")).design
        assertEquals("#0C0D10", linear?.light?.background)
        assertEquals(linear?.light, linear?.dark)
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
