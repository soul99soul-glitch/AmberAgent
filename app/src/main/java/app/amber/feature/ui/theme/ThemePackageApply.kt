package app.amber.feature.ui.theme

import app.amber.core.settings.ChatFontFamily
import app.amber.core.settings.DisplaySetting

/**
 * P8-09 — 主题包导出：把当前自定义主题（DisplaySetting 中用户可见的主题旋钮）
 * 序列化为 [ThemePackage]。导出的 token 全部来自 allowlist，可被
 * [ThemePackageValidator] 校验通过并被 [ThemePackageApplier] 原样应用。
 */
object ThemePackageExporter {

    /** 导出包使用固定 id（「当前自定义主题」的稳定标识，round-trip 可复现）。 */
    const val EXPORTED_PACKAGE_ID = "custom-theme"

    fun export(displaySetting: DisplaySetting): ThemePackage = ThemePackage(
        schemaVersion = ThemePackage.CURRENT_SCHEMA_VERSION,
        id = EXPORTED_PACKAGE_ID,
        name = "我的自定义主题",
        colors = mapOf(
            "baseFamily" to displaySetting.amberBaseFamily,
            "accent" to displaySetting.accentColor,
        ),
        fonts = mapOf(
            "chatFontFamily" to displaySetting.chatFontFamily.name.lowercase(),
            "fontSizeRatio" to displaySetting.fontSizeRatio.toString(),
        ),
        layout = mapOf(
            "showUserAvatar" to displaySetting.showUserAvatar.toString(),
            "showAssistantBubble" to displaySetting.showAssistantBubble.toString(),
        ),
    )
}

/**
 * P8-09 — 主题包应用（纯函数部分）：把 allowlist 内的 token 映射到
 * [DisplaySetting]。未知 token 不参与；值非法时抛 [IllegalArgumentException]
 * （导入时已校验，这里作为应用前最后一道防线）。
 */
object ThemePackageApplier {

    fun applyTokens(pkg: ThemePackage, current: DisplaySetting): DisplaySetting {
        var next = current
        pkg.colors["baseFamily"]?.let { value ->
            next = next.copy(amberBaseFamily = value)
        }
        pkg.colors["accent"]?.let { value ->
            next = next.copy(accentColor = value)
        }
        pkg.fonts["chatFontFamily"]?.let { value ->
            val family = ChatFontFamily.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("无效的 chatFontFamily：$value")
            next = next.copy(chatFontFamily = family)
        }
        pkg.fonts["fontSizeRatio"]?.let { value ->
            val ratio = value.toFloatOrNull() ?: throw IllegalArgumentException("无效的 fontSizeRatio：$value")
            next = next.copy(fontSizeRatio = ratio)
        }
        pkg.layout["showUserAvatar"]?.let { value ->
            next = next.copy(showUserAvatar = value.toBooleanStrict())
        }
        pkg.layout["showAssistantBubble"]?.let { value ->
            next = next.copy(showAssistantBubble = value.toBooleanStrict())
        }
        return next
    }
}
