package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.Screen
import app.amber.core.model.Assistant
import app.amber.core.model.MessageNode
import app.amber.feature.ui.components.richtext.buildMarkdownPreviewHtml
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.theme.NotoSerifSC
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.core.utils.copyMessageToClipboard
import app.amber.core.settings.ChatFontFamily
import app.amber.core.utils.base64Encode

internal fun List<UIMessagePart>.hasRenderableChatMessageContent(): Boolean {
    return any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.MiniApp -> part.appId.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            is UIMessagePart.Tool -> true
        }
    }
}

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
    onStreamingVisibleFrame: (() -> Unit)? = null,
    deferStreamingParse: Boolean = false,
    actionRowHeightCache: ActionRowHeightCache? = null,
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val textStyle = rememberChatMessageTextStyle()
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val searchPresentation = rememberSearchPresentation(message.parts)
    val searchSources = searchPresentation.sources.takeIf { it.isNotEmpty }
    val searchImageUrls = searchPresentation.imageUrls
    Column(
        modifier = modifier
            .fillMaxWidth()
            .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} measure"),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (message.parts.hasRenderableChatMessageContent()) {
            when (message.role) {
                MessageRole.ASSISTANT -> {
                    ChatMessageAssistantAvatar(
                        message = message,
                        model = model,
                        assistant = assistant,
                        loading = loading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                MessageRole.USER -> {
                    if (settings.showUserAvatar) {
                        ChatMessageUserAvatar(
                            message = message,
                            avatar = settings.userAvatar,
                            nickname = settings.userNickname,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                else -> Unit
            }
        }
        CompositionLocalProvider(
            LocalSearchSources provides searchSources,
            LocalSearchImageUrls provides searchImageUrls,
        ) {
            if (message.role == MessageRole.ASSISTANT) {
                SearchImageGallery(
                    images = searchPresentation.images,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = message.parts,
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
                    onUserMessageLongClick = if (message.role == MessageRole.USER) {
                        { showActionsSheet = true }
                    } else null,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                    onMiniAppModify = onMiniAppModify,
                    onStreamingVisibleFrame = onStreamingVisibleFrame,
                    deferStreamingParse = deferStreamingParse,
                )
            }
        }

        AssistantActionRowSlot(
            message = message,
            node = node,
            loading = loading,
            lastMessage = lastMessage,
            actionRowHeightCache = actionRowHeightCache,
            onRegenerate = onRegenerate,
            onUpdate = onUpdate,
            onOpenActionSheet = {
                showActionsSheet = true
            },
        )
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            onCopy = {
                context.copyMessageToClipboard(message)
            },
            onRegenerate = onRegenerate,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

// 2026-05-14 caveat: do NOT add new `animateContentSizeIf(loading)` callsites in the
// message render path. We had 5 such callsites stacked 4-5 deep during streaming
// (MessageAnnotations + ChainOfThought card + Surface bubble + Markdown wrapper)
// and the result was a hard-to-pin "streaming feels janky" regression: every 200ms
// accumulator flush kicked off a fresh spring at each layer, none of which had
// time to settle before the next chunk arrived. The remaining callers
// (`loading && lastMessage` guards on the action-button column) are fine — they
// wrap a tiny, low-frequency-changing payload (the action buttons row), not the
// streamed text itself.
internal fun Modifier.animateContentSizeIf(enabled: Boolean): Modifier =
    if (enabled) animateContentSize() else this

@Composable
internal fun rememberChatMessageTextStyle(): androidx.compose.ui.text.TextStyle {
    val settings = LocalSettings.current.displaySetting
    return LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            // SERIF: project-bundled Noto Serif SC (covers SC + ASCII). On non-SC scripts
            // it falls back to the platform serif chain.
            ChatFontFamily.SERIF -> NotoSerifSC
            // MONOSPACE: project-bundled JetBrains Mono covers ASCII; CJK glyphs fall back
            // to the system Mono (Noto Sans Mono CJK if installed, otherwise the default).
            ChatFontFamily.MONOSPACE -> JetbrainsMono
        }
    )
}
