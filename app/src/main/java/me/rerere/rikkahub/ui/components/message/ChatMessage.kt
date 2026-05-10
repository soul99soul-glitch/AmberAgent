package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.os.Trace
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetParser
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetSegment
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownParseResult
import me.rerere.rikkahub.ui.components.richtext.MarkdownTopLevelBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.richtext.cachedMarkdownParseResult
import me.rerere.rikkahub.ui.components.richtext.canRenderByTopLevelBlocks
import me.rerere.rikkahub.ui.components.richtext.parseMarkdownContent
import me.rerere.rikkahub.ui.components.richtext.topLevelBlockCount
import me.rerere.rikkahub.ui.components.richtext.topLevelBlockKey
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.ui.utils.amberTraceMeasure
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
) {
    val message = node.messages[node.selectIndex]
    val settings = LocalSettings.current.displaySetting
    val textStyle = rememberChatMessageTextStyle()
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} measure"),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
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
                    ChatMessageUserAvatar(
                        message = message,
                        avatar = settings.userAvatar,
                        nickname = settings.userNickname,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> Unit
            }
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
                onGenerativeWidgetAction = onGenerativeWidgetAction,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSizeIf(loading && lastMessage)
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }
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
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
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

private fun Modifier.animateContentSizeIf(enabled: Boolean): Modifier =
    if (enabled) animateContentSize() else this

@Composable
private fun rememberChatMessageTextStyle(): androidx.compose.ui.text.TextStyle {
    val settings = LocalSettings.current.displaySetting
    return LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = when (settings.chatFontFamily) {
            ChatFontFamily.DEFAULT -> FontFamily.Default
            ChatFontFamily.SERIF -> FontFamily.Serif
            ChatFontFamily.MONOSPACE -> FontFamily.Monospace
        }
    )
}

internal const val ChatMessageVirtualMarkdownMinChars = 600
private const val ChatMessageVirtualMarkdownMinTableLines = 3
private val MarkdownTableLineRegex = Regex("""(?m)^\s*\|.+\|\s*$""")

internal sealed interface ChatMessageVirtualItem {
    val keySuffix: String

    data object Header : ChatMessageVirtualItem {
        override val keySuffix: String = "header"
    }

    data class Thinking(
        val block: MessagePartBlock.ThinkingBlock,
        val index: Int,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = "thinking-$index"
    }

    data class ThinkingStepItem(
        val step: ThinkingStep,
        val blockIndex: Int,
        val stepIndex: Int,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = when (step) {
            is ThinkingStep.ReasoningStep -> "thinking-$blockIndex-reasoning-$stepIndex-${step.reasoning.createdAt}"
            is ThinkingStep.ToolStep -> "thinking-$blockIndex-tool-$stepIndex-${step.tool.toolCallId.ifBlank { step.tool.hashCode().toString() }}"
            // Stable key by runId only — stepIndex/blockIndex shift when surrounding tools
            // collapse during streaming, which would otherwise force the capsule to recreate.
            is ThinkingStep.SubAgentTaskStep -> "thinking-subagent-${step.runId}"
        }
    }

    data class Content(
        val block: MessagePartBlock.ContentBlock,
        val index: Int,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = "content-$index"
    }

    /** Standalone subagent task card — survives ChainOfThought collapse, always visible. */
    data class SubAgent(
        val block: MessagePartBlock.SubAgentBlock,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = "subagent-${block.step.runId}"
    }

    data class MarkdownChild(
        val block: MessagePartBlock.ContentBlock,
        val content: String,
        val parseResult: MarkdownParseResult,
        val blockIndex: Int,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String =
            "content-${block.index}-markdown-$blockIndex-${parseResult.topLevelBlockKey(blockIndex)}"
    }

    data object Footer : ChatMessageVirtualItem {
        override val keySuffix: String = "footer"
    }
}

internal fun MessageNode.chatMessageVirtualizationPrewarmTexts(
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    lastMessage: Boolean,
): List<String> {
    val message = currentMessage
    if (message.role != MessageRole.ASSISTANT || (loading && lastMessage)) {
        return emptyList()
    }
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            part.text
                .replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                )
                .takeUnless(GenerativeWidgetParser::containsWidgetFence)
                ?.takeIf(::shouldVirtualizeMarkdownContent)
        }
}

internal fun buildChatMessageVirtualItems(
    node: MessageNode,
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    lastMessage: Boolean,
): List<ChatMessageVirtualItem>? {
    val message = node.currentMessage
    if (message.role != MessageRole.ASSISTANT || (loading && lastMessage)) {
        return null
    }

    val groupedParts = message.parts.groupMessageParts()
    val thinkingStepCount = groupedParts.sumOf { block ->
        if (block is MessagePartBlock.ThinkingBlock) block.steps.size else 0
    }
    val shouldSplitComplexMessage = groupedParts.size > 2 || thinkingStepCount > 1 || message.parts.size > 4
    var hasVirtualMarkdown = false
    val bodyItems = buildList {
        groupedParts.fastForEachIndexed { index, block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> {
                    if (shouldSplitComplexMessage && block.steps.size > 1) {
                        block.steps.fastForEachIndexed { stepIndex, step ->
                            add(ChatMessageVirtualItem.ThinkingStepItem(step, index, stepIndex))
                        }
                    } else {
                        add(ChatMessageVirtualItem.Thinking(block, index))
                    }
                }
                is MessagePartBlock.ContentBlock -> {
                    val part = block.part
                    if (part is UIMessagePart.Text) {
                        val content = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.ASSISTANT,
                            visual = true,
                        )
                        if (GenerativeWidgetParser.containsWidgetFence(content)) {
                            add(ChatMessageVirtualItem.Content(block, index))
                            return@fastForEachIndexed
                        }
                        val shouldVirtualize = shouldVirtualizeMarkdownContent(content)
                        val parseResult = if (shouldVirtualize) {
                            cachedMarkdownParseResult(content) ?: parseMarkdownContent(content)
                        } else {
                            null
                        }
                        if (shouldVirtualize &&
                            parseResult != null &&
                            parseResult.canRenderByTopLevelBlocks()
                        ) {
                            hasVirtualMarkdown = true
                            repeat(parseResult.topLevelBlockCount()) { blockIndex ->
                                add(
                                    ChatMessageVirtualItem.MarkdownChild(
                                        block = block,
                                        content = content,
                                        parseResult = parseResult,
                                        blockIndex = blockIndex,
                                    )
                                )
                            }
                        } else {
                            add(ChatMessageVirtualItem.Content(block, index))
                        }
                    } else {
                        add(ChatMessageVirtualItem.Content(block, index))
                    }
                }

                is MessagePartBlock.SubAgentBlock -> {
                    add(ChatMessageVirtualItem.SubAgent(block))
                }
            }
        }
    }

    if (!hasVirtualMarkdown && !shouldSplitComplexMessage) return null
    return buildList {
        add(ChatMessageVirtualItem.Header)
        addAll(bodyItems)
        add(ChatMessageVirtualItem.Footer)
    }
}

private fun shouldVirtualizeMarkdownContent(content: String): Boolean {
    if (content.length >= ChatMessageVirtualMarkdownMinChars) return true
    val tableLineCount = MarkdownTableLineRegex.findAll(content)
        .take(ChatMessageVirtualMarkdownMinTableLines)
        .count()
    return tableLineCount >= ChatMessageVirtualMarkdownMinTableLines
}

@Composable
internal fun ChatMessageVirtualItemContent(
    node: MessageNode,
    item: ChatMessageVirtualItem,
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
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
) {
    val message = node.currentMessage
    val textStyle = rememberChatMessageTextStyle()
    when (item) {
        ChatMessageVirtualItem.Header -> {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} header measure"),
                horizontalAlignment = Alignment.Start,
            ) {
                if (!message.parts.isEmptyUIMessage()) {
                    ChatMessageAssistantAvatar(
                        message = message,
                        model = model,
                        assistant = assistant,
                        loading = loading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        is ChatMessageVirtualItem.Thinking -> {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    // SubAgentTaskStep is fanned back out to its underlying tools — MessagePartsBlock
                    // calls groupMessageParts again and will re-coalesce them into one card.
                    parts = item.block.steps.flatMap { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> listOf(step.reasoning)
                            is ThinkingStep.ToolStep -> listOf(step.tool)
                            is ThinkingStep.SubAgentTaskStep -> step.tools
                        }
                    },
                    annotations = emptyList(),
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                )
            }
        }

        is ChatMessageVirtualItem.ThinkingStepItem -> {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = when (val step = item.step) {
                        is ThinkingStep.ReasoningStep -> listOf(step.reasoning)
                        is ThinkingStep.ToolStep -> listOf(step.tool)
                        is ThinkingStep.SubAgentTaskStep -> step.tools
                    },
                    annotations = emptyList(),
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                )
            }
        }

        is ChatMessageVirtualItem.Content -> {
            ProvideTextStyle(textStyle) {
                if (item.block.part is UIMessagePart.Text) {
                    VirtualizedAssistantText(
                        fullMessageParts = message.parts,
                        part = item.block.part,
                        assistant = assistant,
                        markdownChild = null,
                        showAssistantBubble = LocalSettings.current.displaySetting.showAssistantBubble,
                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                    )
                } else {
                    MessagePartsBlock(
                        assistant = assistant,
                        role = message.role,
                        parts = listOf(item.block.part),
                        annotations = emptyList(),
                        loading = loading,
                        model = model,
                        onToolApproval = onToolApproval,
                        onToolAnswer = onToolAnswer,
                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                    )
                }
            }
        }

        is ChatMessageVirtualItem.MarkdownChild -> {
            ProvideTextStyle(textStyle) {
                VirtualizedAssistantText(
                    fullMessageParts = message.parts,
                    part = item.block.part as UIMessagePart.Text,
                    assistant = assistant,
                    markdownChild = item,
                    showAssistantBubble = LocalSettings.current.displaySetting.showAssistantBubble,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                )
            }
        }

        is ChatMessageVirtualItem.SubAgent -> {
            ProvideTextStyle(textStyle) {
                SubAgentTaskStepView(
                    step = item.block.step,
                    loading = loading,
                )
            }
        }

        ChatMessageVirtualItem.Footer -> {
            ChatMessageVirtualFooter(
                node = node,
                loading = loading,
                model = model,
                lastMessage = lastMessage,
                onFork = onFork,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onShare = onShare,
                onDelete = onDelete,
                onUpdate = onUpdate,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                textStyle = textStyle,
            )
        }
    }
}

@Composable
private fun VirtualizedAssistantText(
    fullMessageParts: List<UIMessagePart>,
    part: UIMessagePart.Text,
    assistant: Assistant?,
    markdownChild: ChatMessageVirtualItem.MarkdownChild?,
    showAssistantBubble: Boolean,
    onGenerativeWidgetAction: (String) -> Unit,
) {
    val handleClickCitation = rememberClickCitationHandler(fullMessageParts)
    val workspace = workspaceColors()
    MessageSelectionContainer {
        if (markdownChild != null) {
            val blockContent: @Composable () -> Unit = {
                MarkdownTopLevelBlock(
                    data = markdownChild.parseResult,
                    blockIndex = markdownChild.blockIndex,
                    onClickCitation = handleClickCitation,
                )
            }
            if (showAssistantBubble) {
                AssistantBubbleSegment(
                    first = markdownChild.blockIndex == 0,
                    last = markdownChild.blockIndex == markdownChild.parseResult.topLevelBlockCount() - 1,
                    content = blockContent,
                )
            } else {
                blockContent()
            }
        } else {
            if (showAssistantBubble) {
                Surface(
                    modifier = Modifier.widthIn(max = 640.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = workspace.paper,
                    contentColor = workspace.ink,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                        AssistantMarkdownBlockOrWidgets(
                            content = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true,
                            ),
                            streaming = false,
                            onClickCitation = handleClickCitation,
                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                        )
                    }
                }
            } else {
                AssistantMarkdownBlockOrWidgets(
                    content = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        visual = true,
                    ),
                    streaming = false,
                    onClickCitation = handleClickCitation,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                )
            }
        }
    }
}

@Composable
private fun AssistantMarkdownBlockOrWidgets(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean,
    onClickCitation: (String) -> Unit,
    onGenerativeWidgetAction: (String) -> Unit,
) {
    val generativeUiEnabled = LocalSettings.current.agentRuntime.generativeUi.enabled
    if (!generativeUiEnabled || !GenerativeWidgetParser.containsWidgetFence(content)) {
        MarkdownBlock(
            content = content,
            modifier = modifier,
            onClickCitation = onClickCitation,
        )
        return
    }

    val segments = remember(content, streaming) {
        GenerativeWidgetParser.parse(content, streaming = streaming)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            key(index) {
                when (segment) {
                    is GenerativeWidgetSegment.Text -> MarkdownBlock(
                        content = segment.content,
                        onClickCitation = onClickCitation,
                    )

                    is GenerativeWidgetSegment.Widget -> GenerativeWidgetCard(
                        widget = segment,
                        onAction = onGenerativeWidgetAction,
                    )
                    GenerativeWidgetSegment.Loading -> GenerativeWidgetLoading()
                }
            }
        }
    }
}

@Composable
private fun ReasoningWidgetRescue(
    steps: List<ThinkingStep>,
    streaming: Boolean,
    onGenerativeWidgetAction: (String) -> Unit,
) {
    val generativeUiEnabled = LocalSettings.current.agentRuntime.generativeUi.enabled
    if (!generativeUiEnabled) return

    val reasoningTexts = remember(steps) {
        steps.mapNotNull { step ->
            (step as? ThinkingStep.ReasoningStep)?.reasoning?.reasoning
        }
    }
    val segments = remember(reasoningTexts, streaming) {
        reasoningTexts.flatMap { reasoning ->
            val widgetSource = reasoning.reasoningWidgetSource() ?: return@flatMap emptyList()
            GenerativeWidgetParser.parse(widgetSource, streaming = streaming)
                .filter { segment ->
                    segment is GenerativeWidgetSegment.Widget || segment is GenerativeWidgetSegment.Loading
                }
        }
    }
    if (segments.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            key(index) {
                when (segment) {
                    is GenerativeWidgetSegment.Widget -> GenerativeWidgetCard(
                        widget = segment,
                        onAction = onGenerativeWidgetAction,
                    )

                    GenerativeWidgetSegment.Loading -> GenerativeWidgetLoading()
                    is GenerativeWidgetSegment.Text -> Unit
                }
            }
        }
    }
}

internal fun String.reasoningWidgetSource(): String? {
    val marker = Regex("""(?m)^[ \t]*```[ \t]*(?:show-widget|widget|generative-ui)[^\r\n]*""")
    val match = marker.findAll(this).lastOrNull() ?: return null
    return substring(match.range.first).take(24_000)
}

@Composable
private fun AssistantBubbleSegment(
    first: Boolean,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    val workspace = workspaceColors()
    val shape = RoundedCornerShape(
        topStart = if (first) 8.dp else 0.dp,
        topEnd = if (first) 8.dp else 0.dp,
        bottomStart = if (last) 8.dp else 0.dp,
        bottomEnd = if (last) 8.dp else 0.dp,
    )
    Box(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .clip(shape)
            .background(workspace.paper)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val half = stroke / 2f
                val radius = 8.dp.toPx()
                val right = size.width - half
                val bottom = size.height - half
                val topInset = if (first) radius else 0f
                val bottomInset = if (last) radius else 0f

                drawLine(
                    color = workspace.hairline,
                    start = Offset(half, topInset),
                    end = Offset(half, size.height - bottomInset),
                    strokeWidth = stroke,
                )
                drawLine(
                    color = workspace.hairline,
                    start = Offset(right, topInset),
                    end = Offset(right, size.height - bottomInset),
                    strokeWidth = stroke,
                )
                if (first) {
                    drawLine(
                        color = workspace.hairline,
                        start = Offset(radius, half),
                        end = Offset(size.width - radius, half),
                        strokeWidth = stroke,
                    )
                    drawArc(
                        color = workspace.hairline,
                        startAngle = 180f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(half, half),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = workspace.hairline,
                        startAngle = 270f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(size.width - radius * 2f - half, half),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke),
                    )
                }
                if (last) {
                    drawLine(
                        color = workspace.hairline,
                        start = Offset(radius, bottom),
                        end = Offset(size.width - radius, bottom),
                        strokeWidth = stroke,
                    )
                    drawArc(
                        color = workspace.hairline,
                        startAngle = 90f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(half, size.height - radius * 2f - half),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke),
                    )
                    drawArc(
                        color = workspace.hairline,
                        startAngle = 0f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(size.width - radius * 2f - half, size.height - radius * 2f - half),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(stroke),
                    )
                }
            }
            .padding(
                start = 13.dp,
                end = 13.dp,
                top = if (first) 9.dp else 0.dp,
                bottom = if (last) 9.dp else 0.dp,
            )
    ) {
        content()
    }
}

@Composable
private fun ChatMessageVirtualFooter(
    node: MessageNode,
    loading: Boolean,
    model: Model?,
    lastMessage: Boolean,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onTranslate: ((UIMessage, Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    val message = node.currentMessage
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val showActions = if (lastMessage) {
        !loading
    } else {
        message.parts.isEmptyUIMessage().not()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} footer measure"),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProvideTextStyle(textStyle) {
            MessageAnnotations(annotations = message.annotations, loading = loading)

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSizeIf(loading && lastMessage)
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }
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
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
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

@Composable
private fun TraceChatComposable(section: String, content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG) {
        Trace.beginSection(section)
    }
    content()
    if (BuildConfig.DEBUG) {
        Trace.endSection()
    }
}

@Composable
private fun MessageSelectionContainer(
    content: @Composable () -> Unit,
) {
    TraceChatComposable("Amber MessagePartsBlock SelectionContainer") {
        SelectionContainer {
            content()
        }
    }
}

@Composable
private fun rememberClickCitationHandler(parts: List<UIMessagePart>): (String) -> Unit {
    val context = LocalContext.current
    val partsState by rememberUpdatedState(parts)
    return remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAnnotations(
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
) {
    if (annotations.isEmpty()) return

    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Column(
        modifier = Modifier.animateContentSizeIf(loading),
    ) {
        var expand by remember { mutableStateOf(false) }
        if (expand) {
            ProvideTextStyle(
                MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRoundRect(
                                color = contentColor.copy(alpha = 0.2f),
                                size = Size(width = 10f, height = size.height),
                            )
                        }
                        .padding(start = 16.dp)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    annotations.fastForEachIndexed { index, annotation ->
                        when (annotation) {
                            is UIMessageAnnotation.UrlCitation -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = buildAnnotatedString {
                                            append("${index + 1}. ")
                                            withLink(LinkAnnotation.Url(annotation.url)) {
                                                append(annotation.title.urlDecode())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = {
                expand = !expand
            }
        ) {
            Text(stringResource(R.string.citations_count, annotations.size))
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val workspace = workspaceColors()

    // 消息输出HapticFeedback
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)
    val handleClickCitation = rememberClickCitationHandler(parts)
    val hapticEnabled = settings.displaySetting.enableMessageGenerationHapticEffect
    if (loading && hapticEnabled) {
        val hapticFeedback = LocalHapticFeedback.current
        LaunchedEffect(hapticEnabled) {
            snapshotFlow { partsState }
                .debounce(50.milliseconds)
                .collect { nextParts ->
                    if (nextParts.isNotEmpty()) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    }
                }
        }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    val hasVisibleWidgetContent = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Text>().any { part ->
            GenerativeWidgetParser.hasRenderableWidget(part.text)
        }
    }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSizeIf(loading),
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        animateContentChanges = loading,
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                                    )
                                }
                            }

                            is ThinkingStep.SubAgentTaskStep -> {
                                key(step.runId) {
                                    SubAgentTaskStepView(
                                        step = step,
                                        loading = loading,
                                    )
                                }
                            }
                        }
                    }
                    if (!hasVisibleWidgetContent) {
                        ReasoningWidgetRescue(
                            steps = block.steps,
                            streaming = loading,
                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                        )
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        MessageSelectionContainer {
                                if (role == MessageRole.USER) {
                                    Surface(
                                        modifier = Modifier
                                            .widthIn(max = 560.dp)
                                            .animateContentSizeIf(loading),
                                        shape = RoundedCornerShape(6.dp),
                                        color = workspace.note,
                                        contentColor = workspace.ink,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = BorderStroke(1.dp, workspace.hairline),
                                        onClick = { onUserMessageClick?.invoke() },
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .drawWithContent {
                                                    drawRoundRect(
                                                        color = workspace.blue,
                                                        topLeft = Offset.Zero,
                                                        size = Size(width = 3.dp.toPx(), height = size.height),
                                                    )
                                                    drawContent()
                                                }
                                                .padding(start = 15.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
                                        ) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.USER,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation
                                            )
                                        }
                                    }
                                } else {
                                    if (settings.displaySetting.showAssistantBubble) {
                                        Surface(
                                            modifier = Modifier
                                                .widthIn(max = 640.dp)
                                                .animateContentSizeIf(loading),
                                            shape = RoundedCornerShape(8.dp),
                                            color = workspace.paper,
                                            contentColor = workspace.ink,
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                            border = BorderStroke(1.dp, workspace.hairline),
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                                                AssistantMarkdownBlockOrWidgets(
                                                    content = part.text.replaceRegexes(
                                                        assistant = assistant,
                                                        scope = AssistantAffectScope.ASSISTANT,
                                                        visual = true,
                                                    ),
                                                    streaming = loading,
                                                    onClickCitation = handleClickCitation,
                                                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                                                )
                                            }
                                        }
                                    } else {
                                        AssistantMarkdownBlockOrWidgets(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.ASSISTANT,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation,
                                            streaming = loading,
                                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                                            modifier = Modifier
                                                .animateContentSizeIf(loading)
                                        )
                                    }
                                }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }

            is MessagePartBlock.SubAgentBlock -> key("subagent-${block.step.runId}") {
                SubAgentTaskStepView(
                    step = block.step,
                    loading = loading,
                )
            }
        }
    }

    MessageAnnotations(annotations = annotations, loading = loading)
}
