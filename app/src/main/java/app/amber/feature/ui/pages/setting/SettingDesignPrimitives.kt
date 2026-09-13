package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.CardGroupScope
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.workspaceColors

/**
 * The settings mock uses a small mono eyebrow and a hairline to separate each group.  Keep that
 * treatment in one local primitive so individual pages can keep their real controls and state
 * while sharing the same visual rhythm.
 */
@Composable
internal fun SettingSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = workspaceColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SectionLabel(text)
        Spacer(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.hairline),
        )
    }
}

/**
 * A settings section keeps the eyebrow outside the card so the rule belongs to the section,
 * while the card remains a single grouped surface.  This is intentionally a thin wrapper around
 * the existing CardGroup; settings pages keep their current real controls and state wiring.
 */
@Composable
internal fun SettingCardGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable CardGroupScope.() -> Unit,
) {
    val colors = workspaceColors()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingSectionTitle(
            text = title,
            modifier = Modifier.padding(top = 10.dp),
        )
        CardGroup(
            containerColor = colors.paper,
            border = null,
            shadowElevation = 0.dp,
            itemSpacing = 0.dp,
            dividerColor = colors.hairline.copy(alpha = 0.28f),
            dividerStartPadding = 12.dp,
            dividerLeadingOffset = 38.dp,
            content = content,
        )
    }
}

/** Standard row heights from the settings redesign density table. */
internal fun Modifier.settingSingleLine(): Modifier = heightIn(min = 48.dp)

internal fun Modifier.settingTwoLine(): Modifier = heightIn(min = 56.dp)

/** Capsule choice used by settings forms whose alternatives should stay visible. */
@Composable
internal fun <T> SettingSegmentedChoice(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> Unit,
) {
    val colors = workspaceColors()
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.row)
            .border(1.dp, colors.hairline, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .clickable { onSelected(option) }
                    .background(
                        if (option == selected) colors.paper else Color.Transparent,
                        CircleShape,
                    )
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides if (option == selected) colors.ink else colors.muted,
                ) {
                    label(option)
                }
            }
        }
    }
}

/** Standard horizontal inset used by the redesigned settings screens. */
internal val SettingPageHorizontalInset = 16.dp

@Composable
internal fun SettingTileIcon(
    icon: ImageVector,
) {
    WorkspaceLeadingIcon(icon = icon)
}
