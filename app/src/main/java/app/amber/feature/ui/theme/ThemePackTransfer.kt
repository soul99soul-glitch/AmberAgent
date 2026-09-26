package app.amber.feature.ui.theme

import androidx.compose.ui.graphics.toArgb
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.ThemePackDocument
import app.amber.core.settings.themeContrast
import app.amber.core.settings.themeRgb
import app.amber.core.utils.JsonInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

/** iOS's existing v1 file is the interchange format. Legacy Android packages remain readable. */
object ThemePackTransfer {
    val builtinIds = setOf("builtin:WARM", "builtin:SAGE", "sit-terracotta", "pi-steel", "notion-blue")
    private val wireJson = Json(JsonInstant) { explicitNulls = false; prettyPrint = true }

    fun encode(document: ThemePackDocument): String = wireJson.encodeToString(ThemePackDocument.serializer(), document)

    fun decode(json: String): ThemePackDocument = wireJson.decodeFromString(ThemePackDocument.serializer(), json)

    fun validationIssues(document: ThemePackDocument): List<String> = buildList {
        if (document.format != "amber.theme.pack") add("不支持的主题 format：${document.format}")
        if (document.version != 1) add("不支持的主题 version：${document.version}")
        if (document.id.isBlank()) add("主题 id 不能为空")
        if (document.displayName.isBlank()) add("主题名称不能为空")
        fun slot(name: String, value: String?, allowed: Set<String>) {
            if (value != null && value !in allowed) add("$name 的值无效：$value")
        }
        slot("paper", document.paper, setOf("paper", "neutral", "white", "pi", "notion"))
        slot("canvasStyle", document.canvasStyle, setOf("flat", "dotGrid", "lineGrid", "paperGrain"))
        slot("brandMark", document.brandMark, setOf("systemWordmark", "paintAMBER", "serifWordmark"))
        slot("shortcutIconStyle", document.shortcutIconStyle, setOf("phosphorFill", "pixelSit", "systemOutline"))
        slot("chromeTypeface", document.chromeTypeface, setOf("system", "rounded", "serif", "monospace"))
        slot("canvasScope", document.canvasScope, setOf("homeOnly", "shell", "appWide"))
        slot("bubbleChrome", document.bubbleChrome, setOf("standard", "soft", "crisp"))
        slot("glassChrome", document.glassChrome, setOf("standard", "quieter", "solid"))
        slot("emptyArt", document.emptyArt, setOf("none", "character"))
        slot("launchBrand", document.launchBrand, setOf("none", "matchBrand"))
        slot("assetMode", document.assetMode, setOf("builtinOnly"))
        slot("immersivePolicy", document.immersivePolicy, setOf("hidden"))
        val accent = themeRgb(document.accentHex)
        val ink = themeRgb(document.inkHex)
        if (accent == null) add("accentHex 必须是 RGB 十六进制颜色")
        if (ink == null) add("inkHex 必须是 RGB 十六进制颜色")
        if (accent != null && ink != null && themeContrast(accent, ink) < 3.0) add("强调色与文字色对比度至少为 3:1")
        document.design?.let { addAll(it.validationIssues()) }
    }

    fun export(display: DisplaySetting): ThemePackDocument {
        display.themePack?.let { return it }
        val accent = themeRgb(display.accentColor) ?: 0xB8623A
        val ink = if (themeContrast(accent, 0) >= themeContrast(accent, 0xFFFFFF)) 0 else 0xFFFFFF
        val sage = display.amberBaseFamily == "SAGE"
        return ThemePackDocument(
            id = display.appliedThemePackageId ?: ThemePackageExporter.EXPORTED_PACKAGE_ID,
            displayName = "我的自定义主题",
            paper = "paper",
            accentHex = hex(accent),
            inkHex = hex(ink),
            canvasStyle = "dotGrid",
            brandMark = "systemWordmark",
            shortcutIconStyle = "systemOutline",
            chromeTypeface = "system",
            canvasScope = "appWide",
            assetMode = "builtinOnly",
            immersivePolicy = "hidden",
            design = if (sage) ThemeDesign(
                light = palette(baseTokens(AmberBase.SAGE)),
                dark = palette(baseTokens(AmberBase.SAGE_DARK)),
            ) else null,
        )
    }

    fun fromPackage(pkg: ThemePackage, baseline: DisplaySetting): ThemePackDocument =
        pkg.document ?: export(ThemePackageApplier.applyTokens(pkg, baseline)).copy(id = pkg.id, displayName = pkg.name)

    fun asPackage(document: ThemePackDocument): ThemePackage = ThemePackage(
        id = document.id,
        name = document.displayName,
        colors = mapOf("accent" to hex(requireNotNull(themeRgb(document.accentHex)))),
        document = document,
    )

    fun builtin(id: String): ThemePackDocument? = when (id) {
        "builtin:WARM" -> export(DisplaySetting()).copy(id = id, displayName = "点阵 · 陶土")
        "builtin:SAGE" -> export(DisplaySetting(amberBaseFamily = "SAGE")).copy(id = id, displayName = "鼠尾草")
        else -> null
    }

    private val argumentNames = mapOf(
        "id" to "id", "display_name" to "displayName", "paper" to "paper",
        "accent_hex" to "accentHex", "ink_hex" to "inkHex", "canvas_style" to "canvasStyle",
        "brand_mark" to "brandMark", "shortcut_icon_style" to "shortcutIconStyle",
        "chrome_typeface" to "chromeTypeface", "canvas_scope" to "canvasScope",
        "bubble_chrome" to "bubbleChrome", "glass_chrome" to "glassChrome", "empty_art" to "emptyArt",
        "settings_chrome" to "settingsChrome", "launch_brand" to "launchBrand", "design" to "design",
    )

    fun toolPayload(document: ThemePackDocument): JsonObject {
        val encoded = wireJson.encodeToJsonElement(ThemePackDocument.serializer(), document) as JsonObject
        return JsonObject(argumentNames.mapNotNull { (argument, field) -> encoded[field]?.let { argument to it } }.toMap())
    }

    fun toolRecipe(arguments: JsonObject, base: ThemePackDocument? = null): ThemePackDocument {
        val changes = arguments.filterKeys { it !in setOf("action", "base_id", "candidate_digest") }
        require(changes.keys.all { it in argumentNames }) { "未知主题字段：${changes.keys - argumentNames.keys}" }
        require(changes.isNotEmpty()) { "请提供要修改的字段" }
        val mapped = JsonObject(changes.mapKeys { argumentNames.getValue(it.key) })
        val document = if (base == null) {
            require((mapped["id"] as? JsonPrimitive)?.content !in builtinIds) { "新主题不能使用内置 id" }
            val defaults = mapOf(
                "canvasScope" to JsonPrimitive("shell"),
                "assetMode" to JsonPrimitive("builtinOnly"),
                "immersivePolicy" to JsonPrimitive("hidden"),
            )
            // Decode with strict keys: misspelled design fields must not silently reset a recipe.
            strictJson.decodeFromJsonElement<ThemePackDocument>(JsonObject(defaults + mapped))
        } else {
            require(mapped["id"] == null || mapped["id"] == JsonPrimitive(base.id)) { "修改主题不能更换 id" }
            require(changes.keys.any { it != "id" }) { "请提供至少一个要修改的主题字段" }
            require(changes.none { (key, value) -> key != "design" && value == JsonNull }) {
                "只有可选 design 字段可以设为 null，其他主题槽请提供有效值"
            }
            val original = wireJson.encodeToJsonElement(ThemePackDocument.serializer(), base) as JsonObject
            val merged = merge(original, mapped)
            strictJson.decodeFromJsonElement<ThemePackDocument>(merged).let {
                if (base.id in builtinIds) it.copy(id = "${base.id.substringAfter(':')}-custom-${UUID.randomUUID().toString().take(8)}") else it
            }
        }
        val issues = validationIssues(document)
        require(issues.isEmpty()) { issues.joinToString("；") }
        return document
    }

    private val strictJson = Json(wireJson) { ignoreUnknownKeys = false; coerceInputValues = false }

    private fun merge(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
        patch.forEach { (key, value) ->
            this[key] = if (value is JsonObject) merge(this[key] as? JsonObject ?: JsonObject(emptyMap()), value) else value
        }
    })

    private fun palette(tokens: AmberTokens) = ThemeDesign.Palette(
        background = hex(tokens.bg.toArgb()), surface = hex(tokens.surface.toArgb()),
        foreground = hex(tokens.ink.toArgb()), mutedForeground = hex(tokens.ink2.toArgb()),
        border = hex(tokens.line.toArgb()),
    )

    private fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)
}
