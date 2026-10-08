package app.amber.feature.ui.pages.novel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Notebook
import com.composables.icons.lucide.X

/** Project identity stays visible; model and batch controls are revealed on demand. */
@Composable
internal fun NovelWorkspaceHeader(
    title: String,
    subtitle: String,
    controlsEnabled: Boolean,
    onOpenControls: () -> Unit,
    onOpenSettings: () -> Unit,
    backButton: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Surface(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
        color = workspace.canvas,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                backButton()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .novelPressable(onClick = onOpenControls, enabled = controlsEnabled)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Text(
                            title,
                            style = type.screenTitle,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = null,
                            tint = workspace.muted,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        )
                    }
                    Text(
                        subtitle,
                        style = type.secondary,
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                NovelWorkspaceNavButton(Lucide.Settings, stringResource(R.string.novel_project_settings),
                    onClick = onOpenSettings, enabled = controlsEnabled)
            }
            HorizontalDivider(color = workspace.hairline)
        }
    }
}

/** A small visible circle keeps the full navigation hit target. */
@Composable
internal fun NovelWorkspaceNavButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCircle: Boolean = true,
) {
    val workspace = workspaceColors()
    Box(
        modifier.size(48.dp).novelPressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .then(if (showCircle) Modifier.background(workspace.paper)
                    .border(1.dp, workspace.hairline, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (enabled) workspace.muted else workspace.faint,
                modifier = Modifier.size(if (showCircle) 20.dp else 16.dp))
        }
    }
}

@Composable
internal fun NovelWorkspaceSettingsHeader(title: String, onBack: () -> Unit) {
    val workspace = workspaceColors()
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .background(workspace.canvas).heightIn(min = 60.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovelWorkspaceNavButton(Lucide.ArrowLeft, stringResource(R.string.back), onBack)
        Text(title, style = LocalAmberType.current.screenTitle, color = workspace.ink,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        Box(Modifier.size(48.dp))
    }
}

/** Models and project management belong to their own page, separate from writing controls. */
@Composable
internal fun NovelWorkspaceSettingsContent(
    writingModel: String,
    reviewModel: String,
    writingOverride: Boolean,
    reviewOverride: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onWritingModel: () -> Unit,
    onReviewModel: () -> Unit,
    onResetWritingModel: () -> Unit,
    onResetReviewModel: () -> Unit,
    onManageProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(workspace.canvas)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        errorMessage?.let {
            Text(it, style = type.secondary, color = workspace.red,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClearError).padding(horizontal = 12.dp))
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = workspace.paper,
        ) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ProjectControlRow(
                    label = stringResource(R.string.novel_writing_model), value = writingModel,
                    icon = Lucide.PenLine, enabled = !busy, onClick = onWritingModel,
                    reset = if (writingOverride) onResetWritingModel else null,
                    resetDescription = stringResource(R.string.novel_reset_to_global),
                )
                HorizontalDivider(color = workspace.hairline)
                ProjectControlRow(
                    label = stringResource(R.string.novel_review_model_label), value = reviewModel,
                    icon = Lucide.Eye, enabled = !busy, onClick = onReviewModel,
                    reset = if (reviewOverride) onResetReviewModel else null,
                    resetDescription = stringResource(R.string.novel_clear_review_model),
                )
            }
        }
        Text(
            stringResource(R.string.novel_auto_review_note),
            style = type.secondary,
            color = workspace.muted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Surface(shape = RoundedCornerShape(14.dp), color = workspace.paper) {
            ProjectControlRow(
                label = stringResource(R.string.novel_manage_projects),
                icon = Lucide.Notebook, onClick = onManageProjects,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
internal fun NovelWorkspaceOptions(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val visualHeight = maxOf(38.dp, with(LocalDensity.current) { 20.sp.toDp() } + 12.dp)
    val pillColor by animateColorAsState(if (enabled) workspace.paper else workspace.paper.copy(alpha = 0.7f),
        tween(200), label = "novel-options-surface")
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, visualHeight)),
        contentAlignment = Alignment.Center,
    ) {
        val cellWidth = maxWidth / labels.size
        val selectedOffset by animateDpAsState(cellWidth * selected,
            tween(200, easing = FastOutSlowInEasing), label = "novel-options-position")
        Box(Modifier.fillMaxWidth().height(visualHeight).clip(RoundedCornerShape(visualHeight / 2))
            .background(workspace.row.copy(alpha = 0.65f))) {
            Box(Modifier.align(Alignment.CenterStart).offset(x = selectedOffset)
                .width(cellWidth).padding(horizontal = 3.dp).height(visualHeight - 6.dp)
                .background(pillColor, RoundedCornerShape((visualHeight - 6.dp) / 2)))
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val textColor by animateColorAsState(if (!enabled) workspace.faint
                    else if (selected == index) workspace.ink else workspace.muted,
                    tween(200), label = "novel-option-color")
                val textAlpha by animateFloatAsState(
                    if (pressed && enabled) 0.84f else 1f, tween(100), label = "novel-option-press",
                )
                Box(
                    modifier = Modifier.weight(1f).heightIn(min = maxOf(48.dp, visualHeight))
                        .selectable(selected = selected == index, enabled = enabled, role = Role.Tab,
                            interactionSource = interaction, indication = null, onClick = { onSelect(index) })
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = textColor,
                        modifier = Modifier.graphicsLayer { alpha = textAlpha },
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ProjectControlRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
    reset: (() -> Unit)? = null,
    resetDescription: String? = null,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .novelPressable(onClick = onClick, enabled = enabled)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = workspace.muted, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = type.body.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) workspace.ink else workspace.muted)
            value?.let {
                Text(it, style = type.secondary, color = workspace.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        reset?.let {
            NovelWorkspaceNavButton(Lucide.X, resetDescription, onClick = it,
                enabled = enabled, showCircle = false)
        }
        Icon(Lucide.ChevronRight, contentDescription = null, tint = workspace.faint,
            modifier = Modifier.size(14.dp))
    }
}
