package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.toLocalString
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
) {
    val context = LocalContext.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionIconButton(
            imageVector = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            onClick = { context.copyMessageToClipboard(message) },
        )

        MessageActionIconButton(
            imageVector = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            onClick = {
                if (message.role == MessageRole.USER) {
                    showRegenerateConfirm = true
                } else {
                    onRegenerate()
                }
            },
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val settings = LocalSettings.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            MessageActionIconButton(
                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                contentDescription = stringResource(R.string.tts),
                enabled = isAvailable,
                onClick = {
                    if (!isSpeaking) {
                        val text = message.toText()
                        val textToSpeak = if (settings.displaySetting.ttsOnlyReadQuoted) {
                            text.extractQuotedContentAsText() ?: text
                        } else {
                            text
                        }
                        tts.speak(textToSpeak)
                    } else {
                        tts.stop()
                    }
                },
            )

            if (onTranslate != null) {
                MessageActionIconButton(
                    imageVector = HugeIcons.Translate,
                    contentDescription = stringResource(R.string.translate),
                    onClick = {
                        showTranslateDialog = true
                    },
                )
            }
        }

        MessageActionIconButton(
            imageVector = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            onClick = {
                onOpenActionSheet()
            },
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

@Composable
private fun MessageActionIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Borderless variant — message-row actions sit under every bubble, so the
    // outlined paper-and-hairline look was reading as "hardware control panel"
    // clutter. Tap feedback is still provided by Surface's ripple, just without
    // the resting-state outline.
    WorkspaceIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp),
        icon = imageVector,
        contentDescription = contentDescription,
        showBorder = false,
        containerColor = Color.Transparent,
    )
}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val workspace = workspaceColors()
        val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Single hairline-bordered card; rows separated by thin dividers, ripple-on-tap.
            // Replaces the previous "stack of fat tonal cards" look that fought the rest of the
            // Notion-like UI.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = workspace.paper,
                contentColor = workspace.ink,
                border = BorderStroke(1.dp, workspace.hairline),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column {
                    MessageActionRow(
                        icon = HugeIcons.TextSelection,
                        text = stringResource(R.string.select_and_copy),
                        workspace = workspace,
                        onClick = {
                            onDismissRequest()
                            onSelectAndCopy()
                        },
                    )
                    if (hasTextContent) {
                        MessageActionDivider(workspace)
                        MessageActionRow(
                            icon = HugeIcons.WebDesign01,
                            text = stringResource(R.string.render_with_webview),
                            workspace = workspace,
                            onClick = {
                                onDismissRequest()
                                onWebViewPreview()
                            },
                        )
                    }
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = HugeIcons.Edit01,
                        text = stringResource(R.string.edit),
                        workspace = workspace,
                        onClick = {
                            onDismissRequest()
                            onEdit()
                        },
                    )
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = HugeIcons.Share04,
                        text = stringResource(R.string.share),
                        workspace = workspace,
                        onClick = {
                            onDismissRequest()
                            onShare()
                        },
                    )
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = HugeIcons.GitFork,
                        text = stringResource(R.string.create_fork),
                        workspace = workspace,
                        onClick = {
                            onDismissRequest()
                            onFork()
                        },
                    )
                    if (onToggleFavorite != null) {
                        MessageActionDivider(workspace)
                        MessageActionRow(
                            icon = HugeIcons.FavouriteCircle,
                            text = stringResource(
                                if (isFavorite) R.string.chat_message_remove_favorite
                                else R.string.chat_message_add_favorite
                            ),
                            workspace = workspace,
                            onClick = {
                                onDismissRequest()
                                onToggleFavorite()
                            },
                        )
                    }
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = HugeIcons.Delete01,
                        text = stringResource(R.string.delete),
                        workspace = workspace,
                        danger = true,
                        onClick = {
                            onDismissRequest()
                            onDelete()
                        },
                    )
                }
            }

            ProvideTextStyle(
                MaterialTheme.typography.labelSmall.copy(color = workspace.faint)
            ) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: ImageVector,
    text: String,
    workspace: me.rerere.rikkahub.ui.components.ui.WorkspaceColors,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) workspace.red else workspace.muted
    val textColor = if (danger) workspace.red else workspace.ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}

@Composable
private fun MessageActionDivider(
    workspace: me.rerere.rikkahub.ui.components.ui.WorkspaceColors,
) {
    HorizontalDivider(
        thickness = 0.6.dp,
        color = workspace.hairline,
        modifier = Modifier.padding(start = 44.dp),  // align under the text, not under the icon
    )
}
