package app.amber.feature.ui.theme

import app.amber.core.utils.JsonInstant
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8-09 验收测试（校验层，纯 JVM）：
 * - 未知 schemaVersion 拒绝；
 * - 内置主题保留 id（builtin: 前缀）不可被导入包覆盖；
 * - allowlist 之外的 token 不拒绝，但列入提示（保留于 JSON）；
 * - allowlist 内 token 的值非法时拒绝。
 */
class ThemePackageValidatorTest {

    private fun packageJson(
        schemaVersion: Int = ThemePackage.CURRENT_SCHEMA_VERSION,
        id: String = "my-theme",
        name: String = "测试主题",
        colors: Map<String, String> = mapOf("baseFamily" to "WARM", "accent" to "#B8623A"),
        fonts: Map<String, String> = mapOf("chatFontFamily" to "default", "fontSizeRatio" to "1.0"),
        layout: Map<String, String> = mapOf("showUserAvatar" to "true", "showAssistantBubble" to "false"),
    ): String = JsonInstant.encodeToString(
        ThemePackage.serializer(),
        ThemePackage(schemaVersion = schemaVersion, id = id, name = name, colors = colors, fonts = fonts, layout = layout),
    )

    @Test
    fun `unknown schemaVersion is rejected`() {
        val validation = ThemePackageValidator.validateJson(packageJson(schemaVersion = 99))

        assertTrue(validation is ThemePackageValidation.Invalid)
        val issues = (validation as ThemePackageValidation.Invalid).issues
        assertTrue(issues.any { it.contains("schemaVersion") && it.contains("99") })
    }

    @Test
    fun `builtin reserved id cannot be imported over built-in themes`() {
        val validation = ThemePackageValidator.validateJson(packageJson(id = "builtin:WARM"))

        assertTrue(validation is ThemePackageValidation.Invalid)
        val issues = (validation as ThemePackageValidation.Invalid).issues
        assertTrue(issues.any { it.contains("内置主题") })
    }

    @Test
    fun `unknown tokens are kept with a warning instead of rejection`() {
        val json = packageJson(
            colors = mapOf("baseFamily" to "WARM", "accent" to "#B8623A", "customColor" to "#123456"),
            layout = mapOf("showUserAvatar" to "true", "customLayout" to "center"),
        )

        val validation = ThemePackageValidator.validateJson(json)

        assertTrue(validation is ThemePackageValidation.Valid)
        val valid = validation as ThemePackageValidation.Valid
        assertEquals(setOf("颜色:customColor", "布局:customLayout"), valid.unknownTokens.toSet())
        // 未知 token 原样保留在包里（round-trip 不丢）
        assertEquals("#123456", valid.themePackage.colors["customColor"])
    }

    @Test
    fun `invalid allowlisted token values are rejected`() {
        // accent 非法 hex
        assertTrue(
            ThemePackageValidator.validateJson(
                packageJson(colors = mapOf("baseFamily" to "WARM", "accent" to "not-a-color"))
            ) is ThemePackageValidation.Invalid
        )
        // baseFamily 非法枚举
        assertTrue(
            ThemePackageValidator.validateJson(
                packageJson(colors = mapOf("baseFamily" to "NEON", "accent" to "#B8623A"))
            ) is ThemePackageValidation.Invalid
        )
        // fontSizeRatio 超范围
        assertTrue(
            ThemePackageValidator.validateJson(
                packageJson(fonts = mapOf("chatFontFamily" to "serif", "fontSizeRatio" to "9.9"))
            ) is ThemePackageValidation.Invalid
        )
        // 布尔 token 非法
        assertTrue(
            ThemePackageValidator.validateJson(
                packageJson(layout = mapOf("showUserAvatar" to "maybe"))
            ) is ThemePackageValidation.Invalid
        )
    }

    @Test
    fun `malformed json is rejected`() {
        val validation = ThemePackageValidator.validateJson("{ not json")

        assertTrue(validation is ThemePackageValidation.Invalid)
    }
}
