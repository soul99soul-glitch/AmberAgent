package app.amber.feature.ui.pages.sessionhome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.settings.ThemePackDocument
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import app.amber.feature.ui.theme.chromeFontFamily
import app.amber.feature.ui.theme.drawThemeCanvasStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

@Composable
internal fun HomeThemedBrandMark(
    pack: ThemePackDocument,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val components = pack.design?.components
    val override = components?.brandText?.takeIf(String::isNotBlank)
    val serifMark = pack.brandMark == "serifWordmark" && override == null
    val fontSize = components?.brandSize?.toFloat()?.takeIf { it > 0f }?.sp
        ?: if (serifMark) 30.sp else 32.sp
    val tracking = components?.brandTracking?.toFloat()?.sp
        ?: if (serifMark) (-0.5).sp else (-0.64).sp

    if (pack.brandMark == "paintAMBER" && override == null) {
        PixelAmberWordmark(
            color = color,
            modifier = modifier,
        )
    } else {
        val useSerif = serifMark
        val fontFamily = if (useSerif) {
            chromeFontFamily("serif") ?: FontFamily.Default
        } else {
            chromeFontFamily(pack.chromeTypeface) ?: FontFamily.Default
        }
        Text(
            text = override ?: "Amber",
            modifier = modifier,
            color = color,
            fontFamily = fontFamily,
            fontWeight = if (useSerif) FontWeight.Normal else FontWeight.Bold,
            fontStyle = if (useSerif) FontStyle.Italic else FontStyle.Normal,
            fontSize = fontSize,
            letterSpacing = tracking,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

@Composable
private fun PixelAmberWordmark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val cell = 4.dp
    val glyphs = listOf(
        intArrayOf(14, 17, 17, 31, 17, 17, 17), // a
        intArrayOf(17, 27, 21, 17, 17, 17, 17), // m
        intArrayOf(30, 17, 17, 30, 17, 17, 30), // b
        intArrayOf(31, 16, 16, 30, 16, 16, 31), // e
        intArrayOf(30, 17, 17, 30, 20, 18, 17), // r
    )
    Canvas(modifier.width(116.dp).height(28.dp).semantics { contentDescription = "Amber" }) {
        val cellPx = cell.toPx()
        glyphs.forEachIndexed { letterIndex, rows ->
            val letterX = letterIndex * cellPx * 6f
            rows.forEachIndexed { rowIndex, bits ->
                for (column in 0 until 5) {
                    if (bits and (1 shl (4 - column)) != 0) {
                        drawRect(
                            color = color,
                            topLeft = Offset(letterX + column * cellPx, rowIndex * cellPx),
                            size = Size(cellPx, cellPx),
                        )
                    }
                }
            }
        }
    }
}

internal enum class HomePixelShortcut(val rows: IntArray) {
    BOOK(intArrayOf(0, 8184, 16380, 31998, 31998, 25758, 25758, 31998, 25758, 25758, 31998, 31998, 16380, 8184, 0, 0)),
    NOTEBOOK(intArrayOf(0, 16380, 12300, 14316, 12300, 14316, 12300, 14316, 12300, 14316, 12300, 14316, 12300, 16380, 0, 0)),
    CHAT(intArrayOf(0, 8184, 16380, 24582, 24582, 26310, 24582, 26310, 24582, 16380, 8184, 1536, 3072, 6144, 0, 0)),
    GRID(intArrayOf(0, 0, 15420, 15420, 15420, 15420, 0, 0, 15420, 15420, 15420, 15420, 0, 0, 0, 0)),
    GLOBE(intArrayOf(0, 2016, 8184, 14364, 13260, 26598, 25542, 24966, 25542, 26598, 13260, 14364, 8184, 2016, 0, 0)),
}

@Composable
internal fun HomeShortcutIcon(
    systemIcon: ImageVector,
    phosphorIcon: HomeSessionIcon,
    pixelIcon: HomePixelShortcut,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (LocalThemePack.current?.shortcutIconStyle) {
        "phosphorFill" -> Icon(
            imageVector = phosphorIcon.imageVector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )

        "pixelSit" -> PixelShortcutIcon(pixelIcon, contentDescription, tint, modifier)

        else -> Icon(
            imageVector = systemIcon,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}

@Composable
private fun PixelShortcutIcon(
    icon: HomePixelShortcut,
    description: String,
    tint: Color,
    modifier: Modifier,
) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val cell = size.minDimension / 16f
        icon.rows.forEachIndexed { row, bits ->
            for (column in 0 until 16) {
                if (bits and (1 shl (15 - column)) != 0) {
                    drawRect(
                        color = tint,
                        topLeft = Offset(column * cell, row * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

internal fun Modifier.homeGlassChrome(
    hazeState: HazeState?,
    hazeSourceActive: Boolean,
    tokens: AmberTokens,
    shape: Shape,
    glassChrome: String?,
): Modifier {
    val blurActive = hazeSourceActive && hazeState != null
    // 静止时没有模糊层，薄薄一层底色会让纹理直接透过来、边缘发虚；
    // 只有真正在模糊时才允许更通透。
    val opacity = when (glassChrome) {
        "quieter" -> if (blurActive) 0.42f else 0.66f
        "solid" -> if (blurActive) 0.82f else 0.9f
        else -> if (blurActive) 0.55f else 0.78f
    }
    val blurred = if (blurActive) {
        this.hazeEffect(hazeState) {
            backgroundColor = tokens.bg
            blurRadius = 12.dp
        }
    } else {
        this
    }
    return blurred.background(tokens.surface.copy(alpha = opacity), shape)
}

@Composable
internal fun HomeEmptyArtBackground(
    canvasStyle: String?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val opacity = when (canvasStyle) {
        "lineGrid" -> 0.22f
        "paperGrain" -> 0.32f
        else -> 0.28f
    }
    val style = if (canvasStyle == "flat") "dotGrid" else canvasStyle
    Canvas(modifier) {
        drawThemeCanvasStyle(
            style = style,
            isDark = isDark,
            opacityMultiplier = opacity,
        )
    }
}

@Composable
internal fun homeChromeFontFamily(fallback: FontFamily): FontFamily {
    val pack = LocalThemePack.current ?: return fallback
    return chromeFontFamily(pack.chromeTypeface) ?: fallback
}
