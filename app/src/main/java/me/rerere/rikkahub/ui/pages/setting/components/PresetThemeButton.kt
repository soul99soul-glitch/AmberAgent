package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.PresetTheme
import me.rerere.rikkahub.ui.theme.PresetThemes

/**
 * Pulse theme swatch tile.
 *
 * Replaces the older horizontal-scroll concentric-circle button pattern
 * with the Pulse mockup's 3-column grid of aspect-1 swatches. Each tile
 * shows the theme's primary, secondary, and tertiary container colors as
 * three little chips at the bottom plus the theme name as a small caption,
 * with the active tile carrying a 2dp sport-orange border (the same
 * "currently selected" treatment the mockup uses for theme rows in
 * Display Settings).
 *
 * Why this beats the previous concentric-circle button: the mockup tile
 * shows the theme's actual *content* colors at human scale (chips and
 * label) rather than abstract concentric arcs, so the user has a much
 * faster mental model of "what will this look like" without needing to
 * decode the visual abstraction. Plus the 3-column grid gives 7 themes a
 * cleaner spatial layout than a horizontal scroll.
 */
@Composable
fun PresetThemeButton(
    theme: PresetTheme,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.getColorScheme(darkMode)

    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = scheme.background,
        contentColor = scheme.onBackground,
        border = if (selected) {
            // 2dp sport-orange border on the active theme — same "selected"
            // signal the Pulse mockup uses on the active swatch.
            BorderStroke(2.dp, MaterialTheme.colorScheme.secondary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top half: a stacked palette dot row showing primary + secondary
            // + tertiary container hues — the user's "what does this theme
            // feel like" preview.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(scheme.secondary)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(scheme.tertiary)
                )
            }
            // Bottom: theme name label, ink color from the theme so users
            // see how onBackground reads against background — a free
            // typography-contrast preview.
            ProvideTextStyle(
                value = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                )
            ) {
                theme.name()
            }
        }
    }
}

/**
 * 3-column grid of theme swatches per the Pulse mockup's Display Settings
 * spec. Themes are rendered top-to-bottom in their PresetThemes registration
 * order; each row holds 3 tiles with even spacing.
 *
 * Designed for a parent Column that already enforces horizontal padding —
 * we add only the inter-row gap and let each tile's aspectRatio(1) keep
 * heights consistent regardless of screen width.
 */
@Composable
fun PresetThemeButtonGroup(
    themeId: String,
    modifier: Modifier = Modifier,
    onChangeTheme: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // chunked(3) = exactly 3 tiles per row; the last row's missing
        // tiles get rendered as invisible Spacers (filled via a weight
        // modifier) so the surviving tiles don't stretch to fill the row.
        PresetThemes.chunked(3).fastForEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowThemes.fastForEach { theme ->
                    key(theme.id) {
                        PresetThemeButton(
                            theme = theme,
                            selected = theme.id == themeId,
                            modifier = Modifier.weight(1f),
                            onClick = { onChangeTheme(theme.id) },
                        )
                    }
                }
                // Pad short rows so trailing tiles keep the same width as
                // a full row's tiles — without this the last tile alone
                // would stretch to fill the entire row.
                repeat(3 - rowThemes.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PresetThemeButtonPreview() {
    var themeId by remember { mutableStateOf("pulse") }
    PresetThemeButtonGroup(
        themeId = themeId,
        onChangeTheme = { themeId = it }
    )
}
