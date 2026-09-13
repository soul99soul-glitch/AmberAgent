package app.amber.feature.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.core.model.MessageNode

internal enum class ActionFooterMode {
    /** No action row — zero height. */
    Hidden,

    /**
     * Streaming tail — nothing rendered (the action row mounts when generation
     * ends). Kept as a distinct mode so call sites read clearly.
     */
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
 * Shared message tail: citations and the branch selector for alternate replies.
 * Real and virtualized chat paths both render this once, after message body content.
 */
@Composable
internal fun ColumnScope.ChatMessageMessageFooter(
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    textStyle: TextStyle,
    actionFooterMode: ActionFooterMode,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
) {
    ProvideTextStyle(textStyle) {
        MessageAnnotations(annotations = annotations, loading = loading)
    }
    ChatMessageActionFooter(
        mode = actionFooterMode,
        node = node,
        onUpdate = onUpdate,
    )
}

@Composable
internal fun ColumnScope.ChatMessageActionFooter(
    mode: ActionFooterMode,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
) {
    // Ordinary replies have no footer controls. Alternate replies retain
    // their branch selector; message actions are available by long press.
    AnimatedVisibility(
        visible = mode == ActionFooterMode.Visible && node.messages.size > 1,
        enter = fadeIn(animationSpec = tween(220)),
        exit = ExitTransition.None,
    ) {
        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
            interactionEnabled = true,
        )
    }
}
