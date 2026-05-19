package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetParser
import me.rerere.rikkahub.ui.components.richtext.MarkdownParseResult
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.richtext.cachedMarkdownParseResult
import me.rerere.rikkahub.ui.components.richtext.canRenderByTopLevelBlocks
import me.rerere.rikkahub.ui.components.richtext.parseMarkdownContent
import me.rerere.rikkahub.ui.components.richtext.topLevelBlockCount
import me.rerere.rikkahub.ui.components.richtext.topLevelBlockKey
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.utils.amberTraceMeasure
import me.rerere.rikkahub.utils.base64Encode
import java.util.Locale

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
            is ThinkingStep.CouncilTaskStep -> "thinking-council-${step.runId}"
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

    /** Standalone Model Council task card — same lifting rationale as [SubAgent]. */
    data class Council(
        val block: MessagePartBlock.CouncilBlock,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = "council-${block.step.runId}"
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

                is MessagePartBlock.CouncilBlock -> {
                    add(ChatMessageVirtualItem.Council(block))
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
                if (message.parts.hasRenderableChatMessageContent()) {
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
                            is ThinkingStep.CouncilTaskStep -> step.tools
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
                        is ThinkingStep.CouncilTaskStep -> step.tools
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

        is ChatMessageVirtualItem.Council -> {
            ProvideTextStyle(textStyle) {
                CouncilTaskStepView(
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
