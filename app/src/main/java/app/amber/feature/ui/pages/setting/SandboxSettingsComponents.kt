package app.amber.feature.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

/** Shared only by the runtime settings page and its SSH section. */
@Composable
internal fun SandboxCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = workspaceColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.paper,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.hairline.copy(alpha = 0.65f)),
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SandboxSettingsRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = workspaceColors()
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .heightIn(min = if (summary == null) 52.dp else 60.dp)
            .padding(horizontal = 12.dp, vertical = if (summary == null) 2.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Icon(leading, null, Modifier.size(18.dp), tint = colors.muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = type.body.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Text(summary, style = type.secondary, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SandboxDisclosure(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    forceExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    val visible = expanded || forceExpanded
    val rotation by animateFloatAsState(if (visible) 180f else 0f, tween(240), label = "sandboxDisclosure")
    val state = stringResource(if (visible) R.string.chain_of_thought_collapse else R.string.code_block_expand)
    SandboxCard(modifier) {
        SandboxSettingsRow(
            title = title,
            summary = summary,
            onClick = if (forceExpanded) null else ({ expanded = !expanded }),
            onClickLabel = state,
            trailing = { Icon(Lucide.ChevronDown, null, Modifier.size(16.dp).rotate(rotation), tint = workspaceColors().muted) },
        )
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(tween(260)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = workspaceColors().hairline.copy(alpha = 0.55f))
                content()
            }
        }
    }
}

@Composable
internal fun SandboxAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    // Padding belongs to the hit area; only the inner, themed button is 32dp high.
    val actionModifier = if (compact) modifier.padding(vertical = 8.dp).height(32.dp)
        else modifier.heightIn(min = 48.dp)
    val contentPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        else PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(if (compact) 14.dp else 16.dp))
            Text(label, style = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = actionModifier,
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        androidx.compose.material3.OutlinedButton(
            onClick = onClick,
            modifier = actionModifier,
            enabled = enabled,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** Reuse the app's value capsule while bounding long names on this narrow page. */
@Composable
internal fun <T> SandboxSelect(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionToString: @Composable (T) -> String,
    leading: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        app.amber.feature.ui.components.ui.Select(
            options = options,
            selectedOption = selectedOption,
            onOptionSelected = onOptionSelected,
            optionToString = optionToString,
            leading = leading,
            labelMaxWidth = 100.dp,
        )
    }
}
