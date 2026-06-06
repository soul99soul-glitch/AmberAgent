package app.amber.feature.ui.components.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.ui.theme.defaultAmberTextStyles

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
    val scheme = (if (tokens.isDark) darkColorScheme() else lightColorScheme()).copy(
        background = tokens.bg,
        surface = tokens.surface,
        onBackground = tokens.ink,
        onSurface = tokens.ink,
        primary = tokens.accent,
        onPrimary = tokens.accentInk,
    )
    CompositionLocalProvider(
        LocalAmberTokens provides tokens,
        LocalAmberType provides defaultAmberTextStyles(),
    ) {
        MaterialTheme(colorScheme = scheme) {
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
    AmberPreviewScaffold(AmberBase.LIGHT, Color(0xFFB8623A)) { PrimitivesShowcase() }

@Preview(name = "Dark · blue", widthDp = 360)
@Composable
private fun PreviewDarkBlue() =
    AmberPreviewScaffold(AmberBase.DARK, Color(0xFF4F86D6)) { PrimitivesShowcase() }

@Preview(name = "Sage · sage-green", widthDp = 360)
@Composable
private fun PreviewSageGreen() =
    AmberPreviewScaffold(AmberBase.SAGE, Color(0xFF5E9C6E)) { PrimitivesShowcase() }

@Preview(name = "Sage-dark · rose", widthDp = 360)
@Composable
private fun PreviewSageDarkRose() =
    AmberPreviewScaffold(AmberBase.SAGE_DARK, Color(0xFFC2607A)) { PrimitivesShowcase() }
