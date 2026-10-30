package app.amber.feature.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import android.os.Trace
import app.amber.agent.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.isEmptyUIMessage
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import app.amber.core.model.MessageNode
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.feature.ui.components.richtext.MarkdownParseResult
import app.amber.feature.ui.components.richtext.canRenderByTopLevelBlocks
import app.amber.feature.ui.components.richtext.topLevelBlockCount
import app.amber.feature.ui.components.richtext.topLevelBlockKey
import app.amber.feature.ui.context.LocalChatFontScale
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.core.utils.copyMessageToClipboard

internal const val ChatMessageVirtualMarkdownMinChars = 600
private const val ChatMessageVirtualMarkdownMinTableLines = 3

/**
 * Plain-text assistant replies up to this size stay in ONE lazy item. Splitting
 * a short/medium reply into header + per-block + footer items saves offscreen
 * composition but multiplies the fixed per-item cost (subcomposition,
 * selection/bubble wrappers, applyChanges) on the very first viewport. Longer
 * or structurally mixed messages still virtualize.
 */
internal const val ChatMessageSingleItemTextMaxChars = 2_400

/**
 * Settled Markdown is still virtualized for long replies, but several adjacent
 * top-level blocks share one lazy item. Four keeps an offscreen over-compose
 * bounded while removing most of the per-slice subcomposition/applyChanges
 * overhead on the first viewport. Search-image anchoring stays one block per
 * item so images keep their exact placement.
 */
internal const val ChatMessageVirtualMarkdownBlocksPerItem = 4

/**
 * Large paragraphs (or large-font rendering) can make one block taller than
 * the viewport. Never group past this source-text budget, and never merge a
 * single oversized block with a neighbour: a group of four huge blocks would
 * compose several offscreen screens on the first frame.
 */
internal const val ChatMessageVirtualMarkdownGroupMaxChars = 1_600
internal const val ChatMessageVirtualMarkdownBlockMaxChars = 800

/** Pure grouping policy; lengths are source-text lengths per top-level block. */
internal fun markdownBlockGroups(
    blockLengths: List<Int>,
    maxBlocks: Int = ChatMessageVirtualMarkdownBlocksPerItem,
    maxChars: Int = ChatMessageVirtualMarkdownGroupMaxChars,
    maxBlockChars: Int = ChatMessageVirtualMarkdownBlockMaxChars,
): List<IntRange> {
    if (blockLengths.isEmpty()) return emptyList()
    val groups = ArrayList<IntRange>()
    var start = 0
    while (start < blockLengths.size) {
        if (blockLengths[start] > maxBlockChars) {
            groups += start..start
            start++
            continue
        }
        var end = start
        var chars = 0
        while (end < blockLengths.size && end - start < maxBlocks) {
            val length = blockLengths[end].coerceAtLeast(0)
            if (end > start && (length > maxBlockChars || chars + length > maxChars)) break
            chars += length
            end++
        }
        if (end == start) end = start + 1
        groups += start until end
        start = end
    }
    return groups
}

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
        val keyBase: String,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = keyBase
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
        val keyBase: String,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String = keyBase
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
        /** First top-level block in this slice. */
        val blockIndex: Int,
        val keyBase: String,
        val attachments: List<BlockAttachment> = emptyList(),
        /** Number of adjacent top-level blocks rendered by this lazy item. */
        val blockCount: Int = 1,
    ) : ChatMessageVirtualItem {
        override val keySuffix: String =
            "$keyBase-markdown-$blockIndex-${parseResult.topLevelBlockKey(blockIndex)}"
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

/**
 * Markdown sources the settled timeline will parse for this node, keyed exactly as
 * [MessageRenderCache.displayMarkdownText] renders them. Short texts are included too: on a cache
 * miss they are parsed synchronously in composition, and the first parse in a process also pays
 * for lexer initialization.
 */
internal fun MessageNode.chatMessageTimelinePrewarmTexts(
    regexes: List<AssistantRegex>,
    loading: Boolean,
    lastMessage: Boolean,
): List<String> {
    val message = currentMessage
    if (message.role != MessageRole.ASSISTANT && message.role != MessageRole.USER) return emptyList()
    // Only the generating assistant tail is still being rewritten per token.
    // A user tail is settled even while we wait for the assistant to start.
    if (loading && lastMessage && message.role == MessageRole.ASSISTANT) return emptyList()
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            MessageRenderCache.displayMarkdownText(
                text = part.text,
                regexes = regexes,
                role = message.role,
            ).takeUnless {
                it.isBlank() ||
                    message.role == MessageRole.ASSISTANT && (
                        GenerativeWidgetParser.containsWidgetFence(it) ||
                            GenerativeWidgetParser.containsFullHtmlDeckPayload(it) ||
                            it.looksLikeMiniAppJsonPayload()
                        )
            }
        }
}

/** Fills [MessageRenderCache]'s tool JSON entries that tool cards would otherwise parse in composition. */
internal fun MessageNode.prewarmChatMessageToolJson() {
    currentMessage.parts.filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
        MessageRenderCache.toolInputJson(tool.input)
        if (tool.isExecuted) MessageRenderCache.toolOutputJson(tool.output)
    }
}

internal fun buildChatMessageVirtualItems(
    node: MessageNode,
    assistant: List<AssistantRegex>?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    lastMessage: Boolean,
    forceSingleItem: Boolean = false,
    parseMarkdown: (String) -> app.amber.feature.ui.components.richtext.MarkdownParseResult? = MessageRenderCache::markdownParseResult,
): List<ChatMessageVirtualItem>? {
    val message = node.currentMessage
    if (message.role != MessageRole.ASSISTANT || forceSingleItem || (loading && lastMessage)) {
        return null
    }

    // A plain small reply fits in one item and does not benefit from per-block
    // virtualization; the extra lazy items cost more than the offscreen blocks
    // they avoid. Mixed parts (tools/thinking/media) and long replies still use
    // the slice plan below.
    val onlyTextParts = message.parts.isNotEmpty() &&
        message.parts.all { it is UIMessagePart.Text }
    if (onlyTextParts &&
        message.parts.sumOf { (it as UIMessagePart.Text).text.length } <= ChatMessageSingleItemTextMaxChars
    ) {
        return null
    }

    val groupedParts = message.parts.groupMessageParts()
    val shouldSplitComplexMessage = groupedParts.size > 2 || message.parts.size > 4
    val searchPresentation = searchPresentationFor(message.parts)
    val imageAnchorResolver = if (searchPresentation.images.isNotEmpty()) {
        SearchBlockImageAnchorResolver(searchPresentation)
    } else {
        null
    }
    var hasVirtualMarkdown = false
    val contentKeyCounters = HashMap<String, Int>()
    val thinkingKeyCounters = HashMap<String, Int>()
    fun nextContentKey(part: UIMessagePart): String {
        val type = part::class.simpleName ?: "part"
        val ordinal = contentKeyCounters.merge(type, 1, Int::plus)!! - 1
        return "content-$type-$ordinal"
    }

    fun nextThinkingKey(): String {
        val ordinal = thinkingKeyCounters.merge("thinking", 1, Int::plus)!! - 1
        return "thinking-$ordinal"
    }

    val bodyItems = buildList {
        groupedParts.fastForEachIndexed { index, block ->
            when (block) {
                is MessagePartBlock.ThinkingBlock -> {
                    add(ChatMessageVirtualItem.Thinking(block, nextThinkingKey()))
                }
                is MessagePartBlock.ContentBlock -> {
                    val part = block.part
                    val keyBase = nextContentKey(part)
                    if (part is UIMessagePart.Text) {
                        val content = MessageRenderCache.visualRegexText(
                            text = part.text,
                            regexes = assistant.orEmpty(),
                            scope = AssistantAffectScope.ASSISTANT,
                        )
                        if (GenerativeWidgetParser.containsWidgetFence(content) ||
                            GenerativeWidgetParser.containsFullHtmlDeckPayload(content) ||
                            content.looksLikeMiniAppJsonPayload()
                        ) {
                            add(ChatMessageVirtualItem.Content(block, keyBase))
                            return@fastForEachIndexed
                        }
                        val shouldVirtualize = shouldVirtualizeMarkdownContent(content)
                        val parseResult = if (shouldVirtualize) {
                            parseMarkdown(content)
                        } else {
                            null
                        }
                        if (shouldVirtualize &&
                            parseResult != null &&
                            parseResult.canRenderByTopLevelBlocks()
                        ) {
                            hasVirtualMarkdown = true
                            // Search-image groups must stay per-block so every
                            // attachment is rendered directly below its block.
                            val groups = if (imageAnchorResolver == null) {
                                markdownBlockGroups(
                                    parseResult.tree.children.map { child ->
                                        (child.endOffset - child.startOffset).coerceAtLeast(0)
                                    },
                                )
                            } else {
                                parseResult.tree.children.indices.map { it..it }
                            }
                            groups.forEach { group ->
                                val blockIndex = group.first
                                val blockCount = group.last - group.first + 1
                                val attachments = if (blockCount == 1) {
                                    imageAnchorResolver
                                        ?.resolveBlock(
                                            blockNode = parseResult.tree.children[blockIndex],
                                            content = parseResult.preprocessed,
                                        )
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { listOf(BlockAttachment.SearchImages(it)) }
                                        .orEmpty()
                                } else {
                                    emptyList()
                                }
                                add(
                                    ChatMessageVirtualItem.MarkdownChild(
                                        block = block,
                                        content = content,
                                        parseResult = parseResult,
                                        blockIndex = blockIndex,
                                        keyBase = keyBase,
                                        attachments = attachments,
                                        blockCount = blockCount,
                                    )
                                )
                            }
                        } else {
                            add(ChatMessageVirtualItem.Content(block, keyBase))
                        }
                    } else {
                        add(ChatMessageVirtualItem.Content(block, keyBase))
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
    // The footer only renders annotations or the multi-variant branch selector.
    // Emitting an item that draws nothing still costs a lazy subcomposition on
    // the first viewport, so skip it when both are absent.
    val needsFooter = message.annotations.isNotEmpty() || node.messages.size > 1
    return buildList {
        add(ChatMessageVirtualItem.Header(galleryImages = galleryImages))
        addAll(bodyItems)
        if (needsFooter) add(ChatMessageVirtualItem.Footer)
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
    regexes: List<AssistantRegex> = emptyList(),
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onQuote: (String) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    themeTryOnCardTarget: ThemeTryOnCardTarget? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
    /** P3-02: 保存到 Workspace (null 时不显示入口, 由 flag 控制). */
    onSaveToWorkspace: ((UIMessage) -> Unit)? = null,
) {
    val message = node.currentMessage
    val textStyle = rememberChatMessageTextStyle()
    val searchPresentation = rememberSearchPresentation(message.parts)
    val searchSources = searchPresentation.sources.takeIf { it.isNotEmpty }
    val searchImageUrls = searchPresentation.imageUrls
    var showActionsSheet by remember(message.id) { mutableStateOf(false) }
    var showSelectCopySheet by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val chatFontScale = LocalSettings.current.displaySetting.fontSizeRatio
    Box(
        modifier = modifier
            .fillMaxWidth()
            .messageActionsOnLongPress(
                if (!loading && message.parts.hasRenderableChatMessageContent()) {
                    { showActionsSheet = true }
                } else null,
            ),
    ) {
        CompositionLocalProvider(LocalChatFontScale provides chatFontScale) {
        val itemTraceName = if (BuildConfig.DEBUG) {
            "Amber item ${item::class.simpleName}-${message.role.name.lowercase()}"
        } else {
            null
        }
        if (itemTraceName != null) Trace.beginSection(itemTraceName)
        when (item) {
            is ChatMessageVirtualItem.Header -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .amberTraceMeasure("Amber ChatMessage ${message.role.name.lowercase()} header measure"),
                    horizontalAlignment = Alignment.Start,
                ) {
                    if (message.parts.hasRenderableChatMessageContent()) {
                        ChatMessageAssistantAvatar(
                            message = message,
                            model = model,
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
                        regexes = regexes,
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
                        themeTryOnCardTarget = themeTryOnCardTarget,
                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                        onMiniAppModify = onMiniAppModify,
                    )
                }
            }

            is ChatMessageVirtualItem.ThinkingStepItem -> {
                ProvideTextStyle(textStyle) {
                    MessagePartsBlock(
                        regexes = regexes,
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
                        themeTryOnCardTarget = themeTryOnCardTarget,
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
                                regexes = regexes,
                                markdownChild = null,
                                showAssistantBubble = LocalSettings.current.displaySetting.showAssistantBubble,
                                onGenerativeWidgetAction = onGenerativeWidgetAction,
                            )
                        } else {
                            MessagePartsBlock(
                                regexes = regexes,
                                role = message.role,
                                parts = listOf(item.block.part),
                                loading = loading,
                                model = model,
                                onToolApproval = onToolApproval,
                                onToolAnswer = onToolAnswer,
                                onOpenWorkspaceFile = onOpenWorkspaceFile,
                                themeTryOnCardTarget = themeTryOnCardTarget,
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
                            regexes = regexes,
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
                            actionFooterMode = resolveActionFooterMode(
                                role = message.role,
                                lastMessage = lastMessage,
                                loading = loading,
                                hasContent = message.parts.isEmptyUIMessage().not(),
                            ),
                            node = node,
                            onUpdate = onUpdate,
                        )
                    }
                }
            }
        }
        if (itemTraceName != null) Trace.endSection()
        }
    }

    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            onQuote = onQuote,
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
            onSaveToWorkspace = onSaveToWorkspace?.let { callback -> { callback(message) } },
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
