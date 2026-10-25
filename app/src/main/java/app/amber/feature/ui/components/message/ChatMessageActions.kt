package app.amber.feature.ui.components.message

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Quote
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.TextSelect
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.toLocalString
import app.amber.core.utils.toQuoteBlock

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
    /** 引用消息: 把消息文本以引用块形式写入输入框 (有文本内容时可用). */
    onQuote: ((String) -> Unit)? = null,
    /** V3: 重新生成 / 重试 (user 消息时即从该位置重发, assistant 时即重新生成). */
    onRegenerate: (() -> Unit)? = null,
    /** P3-02: 保存消息正文 + 附件引用到 Workspace (flag-gated by caller). */
    onSaveToWorkspace: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun dismissThen(action: () -> Unit) {
        if (pendingAction == null) pendingAction = action
    }

    LaunchedEffect(pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        try {
            // Start after recomposition disables dragging, so another tap cannot
            // interrupt the exit. Keep the dialog/scrim until it is offscreen.
            sheetState.hide()
            if (!sheetState.isVisible) {
                currentOnDismissRequest()
                action()
            }
        } finally {
            pendingAction = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = pendingAction == null,
        // Use one slide-out for key/edge back and action taps. M3's predictive
        // back handler scales this dialog first, exposing its bottom/side edges.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        // Reserve the top safe area outside the sheet's measured content. The
        // default top content inset depends on the sheet offset; a tall menu
        // can grow as that inset changes and moves its expanded anchor again.
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    ) {
        BackHandler { dismissThen {} }
        val workspace = workspaceColors()
        val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                            icon = Lucide.Copy,
                            text = stringResource(R.string.copy),
                            workspace = workspace,
                            onClick = { dismissThen(onCopy) },
                        )
                        MessageActionDivider(workspace)
                    }
                    if (onQuote != null && hasTextContent) {
                        MessageActionRow(
                            icon = Lucide.Quote,
                            text = stringResource(R.string.chat_message_quote),
                            workspace = workspace,
                            onClick = {
                                dismissThen { onQuote(message.toQuoteBlock()) }
                            },
                        )
                        MessageActionDivider(workspace)
                    }
                    if (onSaveToWorkspace != null) {
                        MessageActionRow(
                            icon = Lucide.Archive,
                            text = stringResource(R.string.workspace_save_to_title),
                            workspace = workspace,
                            onClick = { dismissThen(onSaveToWorkspace) },
                        )
                        MessageActionDivider(workspace)
                    }
                    if (onRegenerate != null) {
                        MessageActionRow(
                            icon = Lucide.RotateCw,
                            text = stringResource(R.string.regenerate),
                            workspace = workspace,
                            onClick = { dismissThen(onRegenerate) },
                        )
                        MessageActionDivider(workspace)
                    }
                    MessageActionRow(
                        icon = Lucide.TextSelect,
                        text = stringResource(R.string.select_and_copy),
                        workspace = workspace,
                        onClick = { dismissThen(onSelectAndCopy) },
                    )
                    // V3: 移除 "render_with_webview" (网页视图渲染) 项
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = Lucide.Pencil,
                        text = stringResource(R.string.edit),
                        workspace = workspace,
                        onClick = { dismissThen(onEdit) },
                    )
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = Lucide.Share2,
                        text = stringResource(R.string.share),
                        workspace = workspace,
                        onClick = { dismissThen(onShare) },
                    )
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = Lucide.GitFork,
                        text = stringResource(R.string.create_fork),
                        workspace = workspace,
                        onClick = { dismissThen(onFork) },
                    )
                    if (onToggleFavorite != null) {
                        MessageActionDivider(workspace)
                        MessageActionRow(
                            icon = Lucide.Heart,
                            text = stringResource(
                                if (isFavorite) R.string.chat_message_remove_favorite
                                else R.string.chat_message_add_favorite
                            ),
                            workspace = workspace,
                            onClick = { dismissThen(onToggleFavorite) },
                        )
                    }
                    MessageActionDivider(workspace)
                    MessageActionRow(
                        icon = Lucide.Trash2,
                        text = stringResource(R.string.delete),
                        workspace = workspace,
                        danger = true,
                        onClick = { dismissThen(onDelete) },
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
