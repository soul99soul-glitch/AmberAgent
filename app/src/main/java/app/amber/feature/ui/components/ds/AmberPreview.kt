package app.amber.feature.ui.components.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.AmberShapes
import app.amber.feature.ui.theme.AmberTypography
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.ui.theme.defaultAmberTextStyles
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme

/**
 * Lightweight preview harness for the Graphite design system — provides [LocalAmberTokens] /
 * [LocalAmberType] for a chosen base × accent WITHOUT the Koin-backed AmberAgentTheme, so
 * `@Preview` works in Android Studio. Use to visually verify the mono/sans split, flat/hairline
 * surfaces, and accent/signal behavior across all 4 bases × the curated accents (design §10).
 */
@Composable
fun AmberPreviewScaffold(
    base: AmberBase,
    accent: Color,
    content: @Composable () -> Unit,
) {
    val tokens = buildAmberTokens(base, accent)
    val chatTheme = tokens.toChatTheme()
    val scheme = (if (tokens.isDark) darkColorScheme() else lightColorScheme()).copy(
        background = tokens.bg,
        onBackground = tokens.ink,
        surface = tokens.surface,
        onSurface = tokens.ink,
        surfaceVariant = tokens.codeBg,
        onSurfaceVariant = tokens.ink2,
        surfaceContainerLowest = tokens.bg,
        surfaceContainerLow = tokens.surface,
        surfaceContainer = tokens.surface2,
        surfaceContainerHigh = tokens.surface2,
        surfaceContainerHighest = tokens.raised,
        surfaceBright = tokens.raised,
        surfaceDim = tokens.bg,
        surfaceTint = tokens.accent,
        primary = tokens.accent,
        onPrimary = tokens.accentInk,
        primaryContainer = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        onPrimaryContainer = chatTheme.accentDeep,
        primaryFixed = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        primaryFixedDim = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        onPrimaryFixed = chatTheme.accentDeep,
        onPrimaryFixedVariant = chatTheme.accentDeep,
        secondary = tokens.accent,
        onSecondary = tokens.accentInk,
        secondaryContainer = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        onSecondaryContainer = chatTheme.accentDeep,
        secondaryFixed = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        secondaryFixedDim = chatTheme.accentSoft.compositeOver(chatTheme.paper),
        onSecondaryFixed = chatTheme.accentDeep,
        onSecondaryFixedVariant = chatTheme.accentDeep,
        tertiary = chatTheme.accentDeep,
        onTertiary = chatTheme.onAccent,
        tertiaryContainer = if (tokens.isDark) chatTheme.accentSoft else chatTheme.accentTint,
        onTertiaryContainer = if (tokens.isDark) chatTheme.accent else chatTheme.accentDeep,
        tertiaryFixed = if (tokens.isDark) chatTheme.accentSoft else chatTheme.accentTint,
        tertiaryFixedDim = if (tokens.isDark) chatTheme.accentSoft else chatTheme.accentTint,
        onTertiaryFixed = if (tokens.isDark) chatTheme.accent else chatTheme.accentDeep,
        onTertiaryFixedVariant = if (tokens.isDark) chatTheme.accent else chatTheme.accentDeep,
        inverseSurface = chatTheme.ink,
        inverseOnSurface = chatTheme.paper,
        inversePrimary = chatTheme.accentTint,
        outline = chatTheme.outlineStrong,
        outlineVariant = chatTheme.outlineSoft,
        error = Color(0xFFC2554E),
        onError = Color.Black,
        errorContainer = Color(0xFFC2554E).copy(alpha = 0.12f).compositeOver(chatTheme.paper),
        onErrorContainer = chatTheme.ink,
    )
    CompositionLocalProvider(
        LocalAmberTokens provides tokens,
        LocalAmberType provides defaultAmberTextStyles(),
        LocalChatTheme provides chatTheme,
        LocalContentColor provides tokens.ink,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AmberTypography,
            shapes = AmberShapes,
        ) {
            Column(
                modifier = Modifier
                    .background(tokens.bg)
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun PrimitivesShowcase() {
    val t = LocalAmberTokens.current
    val ty = LocalAmberType.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text("amber", style = ty.meta.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp), color = t.ink)
        BlinkingCursor()
    }
    SectionLabel("AGENT")
    Text("claude-sonnet-4-5 · 200K · \$3/M", style = ty.meta, color = t.ink2)
    Text("人读内容用无衬线，机器事实用等宽——这就是品牌。", style = ty.body, color = t.ink)
    AmberCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Text("live", style = ty.meta, color = t.ink2)
                LiveDot(idle = true)
                Text("idle", style = ty.meta, color = t.ink3)
            }
            Hairline()
            Text("// CONTEXT", style = ty.eyebrow, color = t.accent)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BtnInk("Ink") {}
        BtnAccent("Accent") {}
    }
}

@Preview(name = "Light · terracotta", widthDp = 360)
@Composable
private fun PreviewLightTerracotta() =
    AmberPreviewScaffold(AmberBase.LIGHT, AmberAccents[0].hex) { PrimitivesShowcase() }

@Preview(name = "Dark · blue", widthDp = 360)
@Composable
private fun PreviewDarkBlue() =
    AmberPreviewScaffold(AmberBase.DARK, AmberAccents[2].hex) { PrimitivesShowcase() }

@Preview(name = "Sage · sage-green", widthDp = 360)
@Composable
private fun PreviewSageGreen() =
    AmberPreviewScaffold(AmberBase.SAGE, AmberAccents[1].hex) { PrimitivesShowcase() }

@Preview(name = "Sage-dark · rose", widthDp = 360)
@Composable
private fun PreviewSageDarkRose() =
    AmberPreviewScaffold(AmberBase.SAGE_DARK, AmberAccents[4].hex) { PrimitivesShowcase() }
