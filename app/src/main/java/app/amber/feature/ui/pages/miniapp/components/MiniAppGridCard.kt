package app.amber.feature.ui.pages.miniapp.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.AlarmClock
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.PinOff
import com.composables.icons.lucide.FileCode2
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniAppGridCard(
    app: MiniAppEntity,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onVersions: () -> Unit,
    onEditSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    AmberCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 156.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = tokens.surface2,
                    contentColor = tokens.ink2,
                    border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(app.iconEmoji ?: "▣", style = type.sessionTitle)
                    }
                }
                Text(
                    text = app.title,
                    style = type.sessionTitle,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (app.pinned) {
                    Icon(
                        imageVector = Lucide.Pin,
                        contentDescription = stringResource(R.string.miniapp_pinned),
                        modifier = Modifier.size(14.dp),
                        tint = tokens.accent,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = app.description,
                    style = type.secondary,
                    color = workspace.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                WorkspaceIconButton(
                    onClick = { menuExpanded = true },
                    icon = Lucide.EllipsisVertical,
                    contentDescription = stringResource(R.string.miniapp_more_actions),
                    size = 40.dp,
                    iconSize = 18.dp,
                    showBorder = false,
                    containerColor = Color.Transparent,
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (app.pinned) R.string.history_page_unpin else R.string.history_page_pin,
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(if (app.pinned) Lucide.PinOff else Lucide.Pin, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onTogglePinned()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.miniapp_version_history)) },
                        leadingIcon = { Icon(Lucide.AlarmClock, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onVersions()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.miniapp_export_html)) },
                        leadingIcon = { Icon(Lucide.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.miniapp_edit_source)) },
                        leadingIcon = { Icon(Lucide.FileCode2, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEditSource()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.miniapp_rename)) },
                        leadingIcon = { Icon(Lucide.Pencil, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Lucide.Trash2, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
