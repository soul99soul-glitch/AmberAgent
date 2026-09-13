package app.amber.feature.ui.components.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class SwitchSize {
    Small,
    Medium,
    Large
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: SwitchSize = SwitchSize.Small,
    enabled: Boolean = true,
    trackColor: Color = MaterialTheme.colorScheme.primary,
    trackColorUnchecked: Color = MaterialTheme.colorScheme.surfaceContainer,
    thumbColor: Color = MaterialTheme.colorScheme.onPrimary,
    thumbColorUnchecked: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val dimensions = when (size) {
        SwitchSize.Small -> SwitchDimensions(
            trackWidth = 44.dp,
            trackHeight = 26.dp,
            thumbSize = 18.dp,
            thumbPadding = 4.dp
        )

        SwitchSize.Medium -> SwitchDimensions(
            trackWidth = 48.dp,
            trackHeight = 28.dp,
            thumbSize = 20.dp,
            thumbPadding = 4.dp
        )

        SwitchSize.Large -> SwitchDimensions(
            trackWidth = 52.dp,
            trackHeight = 32.dp,
            thumbSize = 24.dp,
            thumbPadding = 4.dp
        )
    }

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) {
            dimensions.trackWidth - dimensions.thumbSize - dimensions.thumbPadding * 2
        } else {
            0.dp
        },
        animationSpec = tween(durationMillis = 150),
        label = "thumbOffset"
    )

    val currentTrackColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        checked -> trackColor
        else -> trackColorUnchecked
    }

    val currentThumbColor = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        checked -> thumbColor
        else -> thumbColorUnchecked
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = dimensions.trackWidth, height = dimensions.trackHeight)
                .clip(RoundedCornerShape(50))
                .background(currentTrackColor)
                .border(1.dp, if (checked) currentTrackColor else MaterialTheme.colorScheme.outline, CircleShape)
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(dimensions.thumbPadding)
                    .offset(x = thumbOffset)
                    .size(dimensions.thumbSize)
                    .shadow(
                        elevation = if (enabled && checked) 1.dp else 0.dp,
                        shape = CircleShape,
                    )
                    .clip(CircleShape)
                    .background(currentThumbColor),
            )
        }
    }
}

private data class SwitchDimensions(
    val trackWidth: Dp,
    val trackHeight: Dp,
    val thumbSize: Dp,
    val thumbPadding: Dp
)

@Composable
@Preview(showBackground = true)
private fun SwitchPreview() {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var checkedSmall by remember { mutableStateOf(true) }
        var checkedMedium by remember { mutableStateOf(true) }
        var checkedLarge by remember { mutableStateOf(true) }
        var unchecked by remember { mutableStateOf(false) }

        Text("Small", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = checkedSmall,
            onCheckedChange = { checkedSmall = it },
            size = SwitchSize.Small
        )

        Text("Medium (Default)", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = checkedMedium,
            onCheckedChange = { checkedMedium = it },
            size = SwitchSize.Medium
        )

        Text("Large", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = checkedLarge,
            onCheckedChange = { checkedLarge = it },
            size = SwitchSize.Large
        )

        Text("Unchecked", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = unchecked,
            onCheckedChange = { unchecked = it },
            size = SwitchSize.Medium
        )

        Text("Disabled", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = true,
            onCheckedChange = {},
            size = SwitchSize.Medium,
            enabled = false
        )
    }
}
