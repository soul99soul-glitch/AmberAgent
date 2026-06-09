package app.amber.feature.ui.components.message

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
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
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
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import app.amber.agent.R
import app.amber.core.model.MessageNode
import app.amber.feature.ui.components.ui.RikkaConfirmDialog
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalTTSState
import app.amber.core.utils.copyMessageToClipboard
import app.amber.core.utils.extractQuotedContentAsText
import app.amber.core.utils.toLocalString

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    interactionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    // V3 instrumentation (DEBUG only): 用户报 SVG 类 message 下方 actions 重复出现两行.
    // 代码路径互斥 (Message vs VirtualMessage entry) — 双行只能来自上游 MessageNode 重复.
    // gate 在 BuildConfig.DEBUG 后 release 不再写 logcat (review P3 #9 修复).
    if (app.amber.agent.BuildConfig.DEBUG) {
        androidx.compose.runtime.SideEffect {
            android.util.Log.w(
                "AmberActions",
                "render msg=${message.id} role=${message.role} parts=${message.parts.size}"
            )
        }
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        MessageActionIconButton(
            imageVector = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            enabled = interactionEnabled,
            onClick = { context.copyMessageToClipboard(message) },
        )

        MessageActionIconButton(
            imageVector = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            enabled = interactionEnabled,
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
                enabled = interactionEnabled && isAvailable,
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
        }

        MessageActionIconButton(
            imageVector = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            enabled = interactionEnabled,
            onClick = {
                onOpenActionSheet()
            },
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
            interactionEnabled = interactionEnabled,
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
    // Borderless, faint variant — message-row actions sit under every assistant turn.
    // Graphite design §6.2/§7: the action row whispers (faint ink), flat & hairline,
    // no glow. Tint = LocalChatTheme.inkFaint (the same faint token the assistant
    // header date uses) instead of full-strength ink. Tap feedback is the Surface
    // ripple; no resting-state outline. Structure (size/transparent/ripple) is kept
    // identical to the previous WorkspaceIconButton so layout is unchanged.
    val tint = app.amber.feature.ui.pages.chat.LocalChatTheme.current.inkFaint
        .copy(alpha = if (enabled) 1f else 0.36f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        contentColor = tint,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                // Match the prior WorkspaceIconButton default glyph size (15dp) so only
                // the tint changes, not the icon's footprint.
                modifier = Modifier.size(15.dp),
            )
        }
    }
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
    /** V3: 一键复制整条消息到剪贴板 (跟旧 actions row 上的 copy 按钮等价). */
    onCopy: (() -> Unit)? = null,
    /** V3: 重新生成 / 重试 (user 消息时即从该位置重发, assistant 时即重新生成). */
    onRegenerate: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
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
                    // V3: copy 提到第一项 (跟旧 actions row 顺序一致)
                    if (onCopy != null) {
                        MessageActionRow(
                            icon = HugeIcons.Copy01,
                            text = stringResource(R.string.copy),
                            workspace = workspace,
                            onClick = {
                                onDismissRequest()
                                onCopy()
                            },
                        )
                        MessageActionDivider(workspace)
                    }
                    if (onRegenerate != null) {
                        MessageActionRow(
                            icon = HugeIcons.Refresh03,
                            text = stringResource(R.string.regenerate),
                            workspace = workspace,
                            onClick = {
                                onDismissRequest()
                                onRegenerate()
                            },
                        )
                        MessageActionDivider(workspace)
                    }
                    MessageActionRow(
                        icon = HugeIcons.TextSelection,
                        text = stringResource(R.string.select_and_copy),
                        workspace = workspace,
                        onClick = {
                            onDismissRequest()
                            onSelectAndCopy()
                        },
                    )
                    // V3: 移除 "render_with_webview" (网页视图渲染) 项
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
    workspace: app.amber.feature.ui.components.ui.WorkspaceColors,
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
    workspace: app.amber.feature.ui.components.ui.WorkspaceColors,
) {
    HorizontalDivider(
        thickness = 0.6.dp,
        color = workspace.hairline,
        modifier = Modifier.padding(start = 44.dp),  // align under the text, not under the icon
    )
}
