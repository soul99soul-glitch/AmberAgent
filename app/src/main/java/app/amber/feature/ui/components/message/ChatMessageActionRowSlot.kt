package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.core.model.MessageNode
import kotlin.uuid.Uuid

internal enum class AssistantActionRowMode {
    Hidden,
    Reserved,
    Visible,
}

internal fun resolveAssistantActionRowMode(
    role: MessageRole,
    lastMessage: Boolean,
    loading: Boolean,
    hasRenderableContent: Boolean,
): AssistantActionRowMode {
    if (role == MessageRole.USER || !hasRenderableContent) return AssistantActionRowMode.Hidden
    if (role == MessageRole.ASSISTANT && loading) {
        return if (lastMessage) AssistantActionRowMode.Reserved else AssistantActionRowMode.Hidden
    }
    return AssistantActionRowMode.Visible
}

class ActionRowHeightCache {
    private val heights = mutableStateMapOf<ActionRowHeightCacheKey, Int>()

    fun heightFor(messageId: Uuid, widthPx: Int): Int? {
        if (widthPx <= 0) return null
        return heights[ActionRowHeightCacheKey(messageId, widthPx)]
    }

    fun record(messageId: Uuid, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        heights[ActionRowHeightCacheKey(messageId, widthPx)] = heightPx
    }
}

private data class ActionRowHeightCacheKey(
    val messageId: Uuid,
    val widthPx: Int,
)

@Composable
internal fun ColumnScope.AssistantActionRowSlot(
    message: UIMessage,
    node: MessageNode,
    loading: Boolean,
    lastMessage: Boolean,
    actionRowHeightCache: ActionRowHeightCache?,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
) {
    val mode = resolveAssistantActionRowMode(
        role = message.role,
        lastMessage = lastMessage,
        loading = loading,
        hasRenderableContent = message.parts.hasRenderableChatMessageContent(),
    )
    if (mode == AssistantActionRowMode.Hidden) return

    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val cachedHeightDp = actionRowHeightCache
            ?.heightFor(message.id, widthPx)
            ?.let { heightPx -> with(density) { heightPx.toDp() } }
            ?: 0.dp
        val reserved = mode == AssistantActionRowMode.Reserved
        val rowModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = cachedHeightDp)
            .onSizeChanged { size ->
                actionRowHeightCache?.record(
                    messageId = message.id,
                    widthPx = widthPx,
                    heightPx = size.height,
                )
            }
            .then(
                if (reserved) {
                    Modifier
                        .alpha(0f)
                        .clearAndSetSemantics {}
                } else {
                    Modifier
                }
            )

        Column(modifier = rowModifier) {
            ChatMessageActionButtons(
                message = message,
                node = node,
                enabled = !reserved,
                onUpdate = onUpdate,
                onRegenerate = onRegenerate,
                onOpenActionSheet = onOpenActionSheet,
            )
        }
    }
}
