package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.isEmptyUIMessage
import app.amber.core.model.MessageNode

internal enum class ActionFooterMode {
    /** No action row — zero height. */
    Hidden,

    /** Reserve layout space while streaming; invisible and non-interactive. */
    Reserved,

    /** Fully visible and interactive. */
    Visible,
}

internal fun resolveActionFooterMode(
    role: MessageRole,
    lastMessage: Boolean,
    loading: Boolean,
    hasContent: Boolean,
): ActionFooterMode {
    if (role == MessageRole.USER) return ActionFooterMode.Hidden
    return when {
        lastMessage && loading -> ActionFooterMode.Reserved
        lastMessage && !loading -> ActionFooterMode.Visible
        hasContent -> ActionFooterMode.Visible
        else -> ActionFooterMode.Hidden
    }
}

/**
 * Shared message tail: citations/token metadata plus the assistant action row.
 * Real and virtualized chat paths both render this once, after message body content.
 */
@Composable
internal fun ColumnScope.ChatMessageMessageFooter(
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    textStyle: TextStyle,
    actionFooterMode: ActionFooterMode,
    message: UIMessage,
    node: MessageNode,
    onRegenerate: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onOpenActionSheet: () -> Unit,
) {
    ProvideTextStyle(textStyle) {
        MessageAnnotations(annotations = annotations, loading = loading)
    }
    ChatMessageActionFooter(
        mode = actionFooterMode,
        message = message,
        node = node,
        onRegenerate = onRegenerate,
        onUpdate = onUpdate,
        onOpenActionSheet = onOpenActionSheet,
    )
}

@Composable
internal fun ColumnScope.ChatMessageActionFooter(
    mode: ActionFooterMode,
    message: UIMessage,
    node: MessageNode,
    onRegenerate: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onOpenActionSheet: () -> Unit,
) {
    if (mode == ActionFooterMode.Hidden) return

    val visible = mode == ActionFooterMode.Visible
    ChatMessageActionButtons(
        message = message,
        onRegenerate = onRegenerate,
        node = node,
        onUpdate = onUpdate,
        onOpenActionSheet = onOpenActionSheet,
        interactionEnabled = visible,
        modifier = Modifier.alpha(if (visible) 1f else 0f),
    )
}
