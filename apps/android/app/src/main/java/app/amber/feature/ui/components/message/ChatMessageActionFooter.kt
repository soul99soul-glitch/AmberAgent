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
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.isEmptyUIMessage
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
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
) {
    // Reserved no longer holds an invisible full-height row: during streaming
    // that reservation parked a ~48dp blank band between the writing head and
    // the input bar for the whole generation (user-visible "big blank during
    // streaming"). The action row now mounts when generation ends. Its height
    // growth rides the list item's animateContentSize spring (still enabled
    // via the drain grace window — ChatListNormalSection), so here only the
    // alpha eases in; a nested height animation is banned by the 2026-05-14
    // single-amortizer rule (ChatMessage.kt:235).
    AnimatedVisibility(
        visible = mode == ActionFooterMode.Visible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = ExitTransition.None,
    ) {
        ChatMessageActionButtons(
            message = message,
            onRegenerate = onRegenerate,
            node = node,
            onUpdate = onUpdate,
            onOpenActionSheet = onOpenActionSheet,
            interactionEnabled = true,
        )
    }
}
