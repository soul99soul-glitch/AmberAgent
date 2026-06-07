package app.amber.feature.ui.components.ds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/**
 * Representative core-screen preview (design §10) — a hand-assembled "chat screen" mock built
 * from the DS primitives + tokens, so the Graphite look can be visually verified across all
 * 4 bases × accents WITHOUT the Koin-injected ViewModels the real screens require. Mirrors the
 * two-line header, context-bar meter, user bubble, assistant `amber` turn + tool row, and the
 * flat-send composer. Mock, not the production screen — its job is theme-matrix verification.
 */
@Composable
private fun CoreScreenMock() {
    val t = LocalAmberTokens.current
    val ty = LocalAmberType.current
    Column(Modifier.fillMaxWidth().background(t.bg)) {
        // ── two-line header + hairline
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Graphite 重构", style = ty.sessionTitle, color = t.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("claude-opus-4-8", style = ty.meta, color = t.ink2)
                    Text(" ▾", style = ty.meta, color = t.ink3)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val heights = listOf(6, 8, 10, 12, 14)
                repeat(5) { i ->
                    Box(
                        Modifier.width(3.dp).height(heights[i].dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (i < 3) t.accent else t.line),
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text("58%", style = ty.meta, color = t.ink3)
            }
        }
        Hairline()

        // ── conversation body
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // user bubble (right, asymmetric radius)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 5.dp, bottomStart = 16.dp))
                        .background(t.userBg)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text("帮我把界面改成石墨灰", style = ty.body, color = t.userInk) }
            }
            // assistant turn
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("amber", style = ty.meta.copy(fontWeight = FontWeight.Bold), color = t.accent)
                    BlinkingCursor(width = 6.dp, height = 13.dp)
                }
                Text("已套用 Terminal × Modern：暖石墨底、赤陶强调、机器事实用等宽。", style = ty.body, color = t.ink)
                // tool row on code-bg
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(t.codeBg)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(t.accent))
                    Text("read_file", style = ty.meta, color = t.accent)
                    Text("ChatTheme.kt", style = ty.meta, color = t.ink3)
                    Spacer(Modifier.weight(1f))
                    Text("✓", style = ty.meta, color = t.signal)
                }
                SectionLabel("CONTEXT")
            }
        }

        // ── composer: surface tray + top hairline; surface-2 surfaces; accent send
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().background(t.surface).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(t.surface2), contentAlignment = Alignment.Center) {
                Text("+", style = ty.body.copy(fontWeight = FontWeight.Bold), color = t.ink2)
            }
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(t.surface2)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("输入消息…", style = ty.body, color = t.ink4) }
            Box(Modifier.size(36.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
                Text("↑", style = ty.body.copy(fontWeight = FontWeight.Bold), color = t.accentInk)
            }
        }
    }
}

@Preview(name = "Core · Light · terracotta", widthDp = 380)
@Composable
private fun PreviewCoreLight() =
    AmberPreviewScaffold(AmberBase.LIGHT, AmberAccents[0].hex) { CoreScreenMock() }

@Preview(name = "Core · Dark · terracotta", widthDp = 380)
@Composable
private fun PreviewCoreDark() =
    AmberPreviewScaffold(AmberBase.DARK, AmberAccents[0].hex) { CoreScreenMock() }

@Preview(name = "Core · Sage · sage-green", widthDp = 380)
@Composable
private fun PreviewCoreSage() =
    AmberPreviewScaffold(AmberBase.SAGE, AmberAccents[1].hex) { CoreScreenMock() }

@Preview(name = "Core · Sage-dark · blue", widthDp = 380)
@Composable
private fun PreviewCoreSageDark() =
    AmberPreviewScaffold(AmberBase.SAGE_DARK, AmberAccents[2].hex) { CoreScreenMock() }

@Preview(name = "Core · Light · purple", widthDp = 380)
@Composable
private fun PreviewCoreLightPurple() =
    AmberPreviewScaffold(AmberBase.LIGHT, AmberAccents[3].hex) { CoreScreenMock() }

@Preview(name = "Core · Dark · rose", widthDp = 380)
@Composable
private fun PreviewCoreDarkRose() =
    AmberPreviewScaffold(AmberBase.DARK, AmberAccents[4].hex) { CoreScreenMock() }
