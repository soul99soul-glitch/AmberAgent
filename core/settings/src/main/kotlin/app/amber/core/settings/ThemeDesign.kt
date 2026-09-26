package app.amber.core.settings

import kotlinx.serialization.Serializable
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow

/** Shared, data-only portion of the amber.theme.pack v1 design contract. */
@Serializable
data class ThemeDesign(
    val light: Palette? = null,
    val dark: Palette? = null,
    val gradient: Gradient? = null,
    val patterns: List<Pattern> = emptyList(),
    val components: Components? = null,
) {
    @Serializable
    data class Palette(
        val background: String,
        val surface: String,
        val foreground: String,
        val mutedForeground: String,
        val border: String,
    )

    @Serializable
    data class Gradient(
        val colors: List<String>,
        val darkColors: List<String>,
        /** Degrees clockwise from the horizontal axis. */
        val angle: Double = 0.0,
    )

    @Serializable
    data class Pattern(
        val kind: String,
        val color: String,
        val opacity: Double,
        val spacing: Double,
        val size: Double,
    )

    @Serializable
    data class Components(
        val cardRadius: Double? = null,
        val bubbleRadius: Double? = null,
        val controlRadius: Double? = null,
        val borderWidth: Double? = null,
        val shadowOpacity: Double? = null,
        val shadowRadius: Double? = null,
        /** Preserved for the portable document; Android does not render the brand wordmark. */
        val brandText: String? = null,
        /** Preserved for the portable document; Android does not render the brand wordmark. */
        val brandSize: Double? = null,
        /** Preserved for the portable document; Android does not render the brand wordmark. */
        val brandTracking: Double? = null,
    )

    /** Returns actionable wire-format issues; an empty design is valid and leaves defaults intact. */
    fun validationIssues(): List<String> = buildList {
        fun validatePalette(name: String, palette: Palette?): Map<String, Int> {
            if (palette == null) return emptyMap()
            val colors = linkedMapOf(
                "background" to palette.background,
                "surface" to palette.surface,
                "foreground" to palette.foreground,
                "mutedForeground" to palette.mutedForeground,
                "border" to palette.border,
            ).mapNotNull { (field, raw) ->
                val color = themeRgb(raw)
                if (color == null) add("$name.$field 必须是 RGB 十六进制颜色")
                color?.let { field to it }
            }.toMap()
            val foreground = colors["foreground"]
            val muted = colors["mutedForeground"]
            val background = colors["background"]
            val surface = colors["surface"]
            if (foreground != null && background != null && surface != null &&
                (themeContrast(foreground, background) < 4.5 || themeContrast(foreground, surface) < 4.5)
            ) {
                add("$name.foreground 对 background 和 surface 的对比度都必须至少为 4.5:1")
            }
            if (muted != null && background != null && surface != null &&
                (themeContrast(muted, background) < 3.0 || themeContrast(muted, surface) < 3.0)
            ) {
                add("$name.mutedForeground 对 background 和 surface 的对比度都必须至少为 3:1")
            }
            return colors
        }

        val lightColors = validatePalette("light", light)
        val darkColors = validatePalette("dark", dark)

        gradient?.let { value ->
            if (light == null || dark == null) {
                add("gradient 需要同时提供 light 和 dark palette")
            }
            if (!value.angle.isFinite()) add("gradient.angle 必须是有限角度")
            validateStops("gradient.colors", value.colors, lightColors["foreground"])
            validateStops("gradient.darkColors", value.darkColors, darkColors["foreground"])
        }

        if (patterns.size > MAX_PATTERN_LAYERS) add("patterns 最多支持 $MAX_PATTERN_LAYERS 层")
        patterns.forEachIndexed { index, pattern ->
            val prefix = "patterns[$index]"
            if (pattern.kind !in PATTERN_KINDS) add("$prefix.kind 不受支持：${pattern.kind}")
            if (themeRgb(pattern.color) == null) add("$prefix.color 必须是 RGB 十六进制颜色")
            if (!pattern.opacity.isFinite() || pattern.opacity !in 0.0..MAX_PATTERN_OPACITY) {
                add("$prefix.opacity 必须在 0 到 $MAX_PATTERN_OPACITY 之间")
            }
            if (!pattern.spacing.isFinite() || pattern.spacing !in MIN_PATTERN_SPACING..MAX_PATTERN_SPACING) {
                add("$prefix.spacing 必须在 $MIN_PATTERN_SPACING 到 $MAX_PATTERN_SPACING 之间")
            }
            if (!pattern.size.isFinite() || pattern.size !in MIN_PATTERN_SIZE..MAX_PATTERN_SIZE) {
                add("$prefix.size 必须在 $MIN_PATTERN_SIZE 到 $MAX_PATTERN_SIZE 之间")
            }
        }

        components?.let { value ->
            value.cardRadius?.let { requireRange("components.cardRadius", it, 0.0, 32.0) }
            value.bubbleRadius?.let { requireRange("components.bubbleRadius", it, 0.0, 28.0) }
            value.controlRadius?.let { requireRange("components.controlRadius", it, 0.0, 28.0) }
            value.borderWidth?.let { requireRange("components.borderWidth", it, 0.0, 3.0) }
            value.shadowOpacity?.let { requireRange("components.shadowOpacity", it, 0.0, 0.35) }
            value.shadowRadius?.let { requireRange("components.shadowRadius", it, 0.0, 24.0) }
            value.brandText?.let { text ->
                val trimmed = text.trim()
                val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(trimmed) }
                var characterCount = 0
                iterator.first()
                while (iterator.next() != BreakIterator.DONE) characterCount++
                if (characterCount !in 1..16) add("components.brandText 去除首尾空格后须为 1 到 16 个字符")
            }
            value.brandSize?.let { requireRange("components.brandSize", it, 20.0, 40.0) }
            value.brandTracking?.let { requireRange("components.brandTracking", it, -2.0, 6.0) }
        }
    }

    private fun MutableList<String>.validateStops(
        field: String,
        colors: List<String>,
        foreground: Int?,
    ) {
        if (colors.size !in MIN_GRADIENT_STOPS..MAX_GRADIENT_STOPS) {
            add("$field 必须包含 $MIN_GRADIENT_STOPS 到 $MAX_GRADIENT_STOPS 个颜色")
        }
        colors.forEachIndexed { index, raw ->
            val color = themeRgb(raw)
            if (color == null) {
                add("$field[$index] 必须是 RGB 十六进制颜色")
            } else if (foreground != null && themeContrast(color, foreground) < MIN_GRADIENT_CONTRAST) {
                add("$field[$index] 与 foreground 的对比度必须至少为 $MIN_GRADIENT_CONTRAST:1")
            }
        }
    }

    private fun MutableList<String>.requireRange(field: String, value: Double, minimum: Double, maximum: Double) {
        if (!value.isFinite() || value !in minimum..maximum) {
            add("$field 必须在 $minimum 到 $maximum 之间")
        }
    }

    companion object {
        const val MAX_PATTERN_LAYERS = 3
        const val MAX_PATTERN_OPACITY = 0.3
        const val MIN_PATTERN_SPACING = 12.0
        const val MAX_PATTERN_SPACING = 120.0
        const val MIN_PATTERN_SIZE = 0.5
        const val MAX_PATTERN_SIZE = 8.0
        const val MIN_GRADIENT_STOPS = 2
        const val MAX_GRADIENT_STOPS = 4
        const val MIN_GRADIENT_CONTRAST = 4.5
        val PATTERN_KINDS: Set<String> = setOf("dots", "grid", "diagonal", "crosses", "waves", "rings")
    }
}

/** Parses iOS-compatible RGB hex into a 24-bit 0xRRGGBB value. */
fun themeRgb(raw: String): Int? {
    val value = raw.trim()
    val digits = when {
        value.startsWith("#") -> value.drop(1)
        value.startsWith("0x", ignoreCase = true) -> value.drop(2)
        else -> value
    }
    if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
    return digits.toIntOrNull(16)
}

/** WCAG relative contrast ratio for two opaque 24-bit RGB colors. */
fun themeContrast(first: Int, second: Int): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    val lighter = max(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(rgb: Int): Double {
    fun linear(channel: Int): Double {
        val value = channel / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    val red = linear((rgb shr 16) and 0xFF)
    val green = linear((rgb shr 8) and 0xFF)
    val blue = linear(rgb and 0xFF)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue
}
