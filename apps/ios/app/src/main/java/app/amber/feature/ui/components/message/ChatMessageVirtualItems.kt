package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyUIMessage
import app.amber.agent.PerfFlags
import app.amber.agent.Screen
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.MessageNode
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.feature.ui.components.richtext.MarkdownParseResult
import app.amber.feature.ui.components.richtext.buildMarkdownPreviewHtml
import app.amber.feature.ui.components.richtext.canRenderByTopLevelBlocks
import app.amber.feature.ui.components.richtext.topLevelBlockCount
import app.amber.feature.ui.components.richtext.topLevelBlockKey
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.core.utils.base64Encode
import app.amber.core.utils.copyMessageToClipboard

internal const val ChatMessageVirtualMarkdownMinChars = 600
private const val ChatMessageVirtualMarkdownMinTableLines = 3
private val MarkdownTableLineRegex = Regex("""(?m)^\s*\|.+\|\s*$""")

internal sealed interface ChatMessageVirtualItem {
    val keySuffix: String

    data class Header(
        val galleryImages: List<SearchImageRef> = emptyList(),
    ) : ChatMessageVirtualItem {
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
        val attachments: List<BlockAttachment> = emptyList(),
    ) : ChatMessageVirtualItem {
        override val keySuffix: String =
            "content-${block.index}-markdown-$blockIndex-${parseResult.topLevelBlockKey(blockIndex)}"
    }

    data object Footer : ChatMessageVirtualItem {
        override val keySuffix: String = "footer"
    }
}

internal sealed interface BlockAttachment {
    data class SearchImages(
        val images: List<SearchImageRef>,
    ) : BlockAttachment
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
                .let { text ->
                    MessageRenderCache.visualRegexText(
                        text = text,
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                    )
                }
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
    val shouldSplitComplexMessage = groupedParts.size > 2 || message.parts.size > 4
    val searchPresentation = deriveSearchPresentation(message.parts)
    val imageAnchorResolver = if (PerfFlags.SEARCH_INLINE_IMAGES && searchPresentation.images.isNotEmpty()) {
        SearchBlockImageAnchorResolver(searchPresentation)
    } else {
        null
    }
    var hasVirtualMarkdown = false
    val bodyItems = buildList {
        groupedParts.fastForEachIndexed { index, block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> {
                    add(ChatMessageVirtualItem.Thinking(block, index))
                }
                is MessagePartBlock.ContentBlock -> {
                    val part = block.part
                    if (part is UIMessagePart.Text) {
                        val content = MessageRenderCache.visualRegexText(
                            text = part.text,
                            assistant = assistant,
                            scope = AssistantAffectScope.ASSISTANT,
                        )
                        if (GenerativeWidgetParser.containsWidgetFence(content)) {
                            add(ChatMessageVirtualItem.Content(block, index))
                            return@fastForEachIndexed
                        }
                        val shouldVirtualize = shouldVirtualizeMarkdownContent(content)
                        val parseResult = if (shouldVirtualize) {
                            MessageRenderCache.markdownParseResult(content)
                        } else {
                            null
                        }
                        if (shouldVirtualize &&
                            parseResult != null &&
                            parseResult.canRenderByTopLevelBlocks()
                        ) {
                            hasVirtualMarkdown = true
                            repeat(parseResult.topLevelBlockCount()) { blockIndex ->
                                val attachments = imageAnchorResolver
                                    ?.resolveBlock(
                                        blockNode = parseResult.tree.children[blockIndex],
                                        content = parseResult.preprocessed,
                                    )
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { listOf(BlockAttachment.SearchImages(it)) }
                                    .orEmpty()
                                add(
                                    ChatMessageVirtualItem.MarkdownChild(
                                        block = block,
                                        content = content,
                                        parseResult = parseResult,
                                        blockIndex = blockIndex,
                                        attachments = attachments,
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
    val galleryImages = imageAnchorResolver?.orphans() ?: searchPresentation.images
    return buildList {
        add(ChatMessageVirtualItem.Header(galleryImages = galleryImages))
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
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
) {
    val message = node.currentMessage
    val textStyle = rememberChatMessageTextStyle()
    val searchPresentation = rememberSearchPresentation(message.parts)
    val searchSources = searchPresentation.sources.takeIf { it.isNotEmpty }
    val searchImageUrls = searchPresentation.imageUrls
    when (item) {
        is ChatMessageVirtualItem.Header -> {
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
                SearchImageGallery(
                    images = item.galleryImages,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                    onMiniAppModify = onMiniAppModify,
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
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onOpenWorkspaceFile = onOpenWorkspaceFile,
                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                    onMiniAppModify = onMiniAppModify,
                )
            }
        }

        is ChatMessageVirtualItem.Content -> {
            CompositionLocalProvider(
                LocalSearchSources provides searchSources,
                LocalSearchImageUrls provides searchImageUrls,
            ) {
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
                            loading = loading,
                            model = model,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            onOpenWorkspaceFile = onOpenWorkspaceFile,
                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                            onMiniAppModify = onMiniAppModify,
                        )
                    }
                }
            }
        }

        is ChatMessageVirtualItem.MarkdownChild -> {
            CompositionLocalProvider(
                LocalSearchSources provides searchSources,
                LocalSearchImageUrls provides searchImageUrls,
            ) {
                ProvideTextStyle(textStyle) {
                    VirtualizedAssistantText(
                        fullMessageParts = message.parts,
                        part = item.block.part as UIMessagePart.Text,
                        assistant = assistant,
                        markdownChild = item,
                        attachments = item.attachments,
                        showAssistantBubble = LocalSettings.current.displaySetting.showAssistantBubble,
                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                    )
                }
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
            CompositionLocalProvider(
                LocalSearchSources provides searchSources,
                LocalSearchImageUrls provides searchImageUrls,
            ) {
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
                    textStyle = textStyle,
                )
            }
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
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    val message = node.currentMessage
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val actionFooterMode = resolveActionFooterMode(
        role = message.role,
        lastMessage = lastMessage,
        loading = loading,
        hasContent = message.parts.isEmptyUIMessage().not(),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} footer measure"),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatMessageMessageFooter(
            annotations = message.annotations,
            loading = loading,
            textStyle = textStyle,
            actionFooterMode = actionFooterMode,
            message = message,
            node = node,
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
