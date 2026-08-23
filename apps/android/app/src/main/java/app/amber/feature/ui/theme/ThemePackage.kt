package app.amber.feature.ui.theme

import app.amber.core.utils.JsonInstant
import kotlinx.serialization.Serializable

/**
 * P8-09 — 主题包 JSON schema（schemaVersion 1）。
 *
 * 结构：颜色 / 字体 / 布局三组 token，值一律为字符串（枚举名 / hex 色值 /
 * 布尔 / 数字字符串）。所有 token 名必须落在 allowlist 内；未知 token
 * 在导入时保留于原始 JSON（round-trip 不丢）并作为警告提示，但不参与应用。
 * 包内没有任何代码或 URL 字段——导入只做解析和取值，不执行任何东西。
 */
@Serializable
data class ThemePackage(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 稳定 id；`builtin:` 前缀为内置主题保留，导入包不可使用。 */
    val id: String,
    val name: String,
    val colors: Map<String, String> = emptyMap(),
    val fonts: Map<String, String> = emptyMap(),
    val layout: Map<String, String> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val BUILTIN_ID_PREFIX = "builtin:"
    }
}

/** P8-09 token allowlist：颜色 / 字体 / 布局各自允许的 token 名。 */
object ThemePackageTokens {
    /** 颜色 token：中性色基（WARM|SAGE）+ 独立强调色（hex）。 */
    val COLOR_TOKENS: Set<String> = setOf("baseFamily", "accent")

    /** 字体 token：聊天气泡字体族 + 字号比例。 */
    val FONT_TOKENS: Set<String> = setOf("chatFontFamily", "fontSizeRatio")

    /** 布局 token：气泡 / 头像显示开关。 */
    val LAYOUT_TOKENS: Set<String> = setOf("showUserAvatar", "showAssistantBubble")

    private val BASE_FAMILIES = setOf("WARM", "SAGE")
    private val FONT_FAMILIES = setOf("default", "serif", "monospace")
    private const val MIN_FONT_SIZE_RATIO = 0.5f
    private const val MAX_FONT_SIZE_RATIO = 2.0f
    private val BOOLEAN_VALUES = setOf("true", "false")

    internal fun isValidColorToken(token: String, value: String): Boolean = when (token) {
        "baseFamily" -> value in BASE_FAMILIES
        "accent" -> isHexColor(value)
        else -> false
    }

    internal fun isValidFontToken(token: String, value: String): Boolean = when (token) {
        "chatFontFamily" -> value in FONT_FAMILIES
        "fontSizeRatio" -> value.toFloatOrNull()?.let { it in MIN_FONT_SIZE_RATIO..MAX_FONT_SIZE_RATIO } == true
        else -> false
    }

    internal fun isValidLayoutToken(token: String, value: String): Boolean =
        token in LAYOUT_TOKENS && value in BOOLEAN_VALUES

    private fun isHexColor(value: String): Boolean {
        if (!value.startsWith("#") || value.length !in 7..9) return false
        return value.drop(1).all { it in "0123456789abcdefABCDEF" }
    }
}

sealed interface ThemePackageValidation {
    data class Valid(
        val themePackage: ThemePackage,
        /** 不在 allowlist 内的 token 名（保留于 JSON，仅提示）。 */
        val unknownTokens: List<String>,
    ) : ThemePackageValidation

    data class Invalid(val issues: List<String>) : ThemePackageValidation
}

/**
 * P8-09 校验器：解析 JSON → 检查 schemaVersion / id / token 值与 allowlist。
 * 拒绝：非 JSON、未知 schemaVersion、保留 id、任何 token 值非法。
 * 提示（不拒绝）：allowlist 之外的 token 名（原样保留）。
 */
object ThemePackageValidator {

    fun validateJson(json: String): ThemePackageValidation {
        val pkg = runCatching { JsonInstant.decodeFromString(ThemePackage.serializer(), json) }
            .getOrNull()
        if (pkg == null) {
            return ThemePackageValidation.Invalid(listOf("不是有效的主题包 JSON"))
        }
        return validatePackage(pkg)
    }

    fun validatePackage(pkg: ThemePackage): ThemePackageValidation {
        val issues = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        if (pkg.schemaVersion != ThemePackage.CURRENT_SCHEMA_VERSION) {
            issues += "不支持的 schemaVersion：${pkg.schemaVersion}（当前支持 ${ThemePackage.CURRENT_SCHEMA_VERSION}）"
        }
        if (pkg.id.isBlank()) {
            issues += "主题包 id 不能为空"
        }
        if (pkg.id.startsWith(ThemePackage.BUILTIN_ID_PREFIX)) {
            issues += "内置主题 id（${ThemePackage.BUILTIN_ID_PREFIX} 前缀）为保留值，导入包不能覆盖内置主题"
        }
        if (pkg.name.isBlank()) {
            issues += "主题包 name 不能为空"
        }
        validateSection(pkg.colors, ThemePackageTokens.COLOR_TOKENS, "颜色", ::isValidColorTokenSafe, issues, unknown)
        validateSection(pkg.fonts, ThemePackageTokens.FONT_TOKENS, "字体", ::isValidFontTokenSafe, issues, unknown)
        validateSection(pkg.layout, ThemePackageTokens.LAYOUT_TOKENS, "布局", ::isValidLayoutTokenSafe, issues, unknown)
        return if (issues.isEmpty()) {
            ThemePackageValidation.Valid(pkg, unknown)
        } else {
            ThemePackageValidation.Invalid(issues)
        }
    }
    private fun validateSection(
        tokens: Map<String, String>,
        allowlist: Set<String>,
        sectionLabel: String,
        isValid: (String, String) -> Boolean,
        issues: MutableList<String>,
        unknown: MutableList<String>,
    ) {
        tokens.forEach { (token, value) ->
            when {
                token !in allowlist -> unknown += "$sectionLabel:$token"
                !isValid(token, value) -> issues += "$sectionLabel token「$token」的值无效：$value"
            }
        }
    }

    private fun isValidColorTokenSafe(token: String, value: String): Boolean =
        runCatching { ThemePackageTokens.isValidColorToken(token, value) }.getOrDefault(false)

    private fun isValidFontTokenSafe(token: String, value: String): Boolean =
        runCatching { ThemePackageTokens.isValidFontToken(token, value) }.getOrDefault(false)

    private fun isValidLayoutTokenSafe(token: String, value: String): Boolean =
        runCatching { ThemePackageTokens.isValidLayoutToken(token, value) }.getOrDefault(false)
}
