package app.amber.feature.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact, single-row card matching the Notion-flavoured visual language. Replaces the
 * previous list-style cards (TTS providers, search services, etc.) where each item used
 * a 16dp-padded `Card` with a 40dp circular leading icon stacked above a separate
 * actions row — that produced ~80-100dp tall items dense with empty space.
 *
 * Visual shape:
 *  - one row, default min-height 56dp (touch-target friendly, but smaller than the prior
 *    two-row cards)
 *  - 12dp horizontal / 10dp vertical padding around the row content
 *  - rounded `MaterialTheme.shapes.medium` corners
 *  - background: `surfaceContainerHigh` when not selected, `primaryContainer @ 0.5α`
 *    when selected (subtle tint, the title also flips to `onPrimaryContainer`)
 *  - clickable when [onClick] is non-null, with the standard Material indication
 *
 * Slots:
 *  - [leading]   small icon (recommend 24dp) — kept optional so callers can omit when
 *                they don't have a meaningful icon (avoids the placeholder-square look)
 *  - [title]     headline string (1 line, ellipsis on overflow)
 *  - [subtitle]  optional secondary string (1 line, ellipsis, onSurfaceVariant)
 *  - [trailing]  free-form `RowScope` so callers can stack icon buttons / tags / drag
 *                handles. `RowScope` lets them use `weight` if needed
 *
 * Not abstracted: list separators, swipe-to-dismiss, expand/collapse — callers compose
 * those externally if needed. This is intentionally a *row* primitive, not a full list.
 */
@Composable
fun NotionListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = 56.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
    val baseModifier = modifier
        .fillMaxWidth()
        .heightIn(min = minHeight)
    // Two distinct Surface variants: the `onClick` overload always exposes a Button role
    // to TalkBack — even when disabled — which would mis-announce a purely decorative row
    // (no onClick supplied) as "disabled button". Pick the right overload up front so
    // accessibility semantics match the actual interactive intent.
    if (onClick != null) {
        Surface(
            modifier = baseModifier,
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            onClick = onClick,
            enabled = enabled,
            content = rowContent,
        )
    } else {
        Surface(
            modifier = baseModifier,
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            content = rowContent,
        )
    }
}
