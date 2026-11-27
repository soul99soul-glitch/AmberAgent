package app.amber.feature.ui.components.message

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.streamToolIndex
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Music
import androidx.compose.foundation.layout.aspectRatio
import com.composables.icons.lucide.Play
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.AssistantRegex
import app.amber.core.settings.ReplyDeliveryMode
import app.amber.core.ai.generative.GenerativeWidgetCopy
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.feature.ui.components.media.VideoPlaybackDialog
import app.amber.feature.ui.components.media.rememberVideoCover
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.StreamingSingleTextMarkdown
import app.amber.feature.ui.components.richtext.MarkPresented
import app.amber.feature.ui.components.richtext.rememberStreamingPresentationLifetime
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.utils.AmberTraceSection
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.modifier.shimmer
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.pages.miniapp.components.MiniAppChatCard
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
    regexes: List<AssistantRegex>,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    /** V3: 长按 user 消息胶囊触发 menu sheet（复制/重试/编辑/分享/收藏/删除）. */
    onUserMessageLongClick: (() -> Unit)? = null,
    onGenerativeWidgetAction: (String) -> Unit = {},
    onMiniAppModify: (String) -> Boolean = { false },
    themeTryOnCardTarget: ThemeTryOnCardTarget? = null,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
    deferStreamingParse: Boolean = false,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val workspace = workspaceColors()
    val generativeWidgetCopy = remember(context) { GenerativeWidgetCopy.from(context) }

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
    val contentBlockKeys = remember(groupedParts) { groupedParts.stableContentBlockKeys() }
    val hasVisibleWidgetContent = remember(parts, generativeWidgetCopy) {
        parts.filterIsInstance<UIMessagePart.Text>().any { part ->
            GenerativeWidgetParser.hasRenderableWidget(part.text, copy = generativeWidgetCopy)
        }
    }
    // A Text block is "the streaming tail" (still being appended to) if and ONLY
    // if it sits at the very end of the grouped block sequence. The first
    // attempt at this fix (indexOfLast { ContentBlock && Text }) was wrong:
    // it kept declaring intermediate Text blocks as the tail when there was
    // only one Text in the message, even if a Tool/Thinking block followed it
    // — e.g. `[Reasoning, Text, Tool(subagent_task)]` would mark idx=1 (Text)
    // as the only-and-therefore-last Text, but it's already sealed off by the
    // trailing Tool. Result: 1.8.6 still showed permanent grey-tail mid-message.
    //
    // Correct rule: blockIdx == groupedParts.lastIndex is the canonical "this
    // is the trailing block right now" test. If it happens to be a Text block,
    // it's streaming; if it's a Tool/Thinking block, the Text branch never
    // reads isStreamingText anyway (the `when (block)` doesn't enter Text).
    val lastBlockIdx = groupedParts.lastIndex
    groupedParts.fastForEachIndexed { blockIdx, block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    val themeTryOnTool = themeTryOnCardTarget?.let { target ->
                        block.steps.asSequence().flatMap { step ->
                            when (step) {
                                is ThinkingStep.ToolStep -> sequenceOf(step.tool)
                                is ThinkingStep.SubAgentTaskStep -> step.tools.asSequence()
                                is ThinkingStep.CouncilTaskStep -> step.tools.asSequence()
                                is ThinkingStep.ReasoningStep -> emptySequence()
                            }
                        }.firstOrNull { it.matchesThemeTryOnCardTarget(target) }
                    }
                    // 2026-05-14: removed outer `Modifier.animateContentSizeIf(loading)` — the
                    // ChainOfThought card was the most visible jank source. Its inner
                    // `animateContentChanges = loading` still drives the step-list spring
                    // (less aggressive, single layer), so the user-visible "新思维步骤滑入"
                    // motion is preserved. Removing the outer Card-level spring is the
                    // free win — it stacked on top of the inner one with no added value.
                    AmberTraceSection("Amber chain of thought") {
                    ChainOfThought(
                        steps = block.steps,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        animateContentChanges = loading,
                        // V3: wrapper 不画 timeline. 改由 ReasoningContent 内部自带 quote
                        // line, 这样 reasoning step 有竖线、tool call step 没竖线 (符合
                        // 语义 — tool 是平级独立调用, 不该被串起来).
                        drawTimeline = false,
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                AmberTraceSection("Amber reasoning step") {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        regexes = regexes,
                                        loading = loading,
                                        framed = true,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                AmberTraceSection("Amber tool step ${step.tool.toolName}") {
                                key(step.tool.stableStepKey()) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        onOpenWorkspaceFile = onOpenWorkspaceFile,
                                        themeTryOnCardTarget = themeTryOnCardTarget,
                                    )
                                }
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

                            is ThinkingStep.CouncilTaskStep -> {
                                // Council steps are normally lifted to top-level CouncilBlock by
                                // groupMessageParts. This branch only fires if a council task slips
                                // into a ThinkingBlock somehow (e.g. fan-out re-render path) — render
                                // the same card so nothing is lost.
                                key(step.runId) {
                                    CouncilTaskStepView(
                                        step = step,
                                        loading = loading,
                                    )
                                }
                            }
                        }
                    }
                    }
                    themeTryOnTool?.let { tool ->
                        ThemeTryOnToolCard(
                            tool = tool,
                            target = themeTryOnCardTarget!!,
                            onToolApproval = onToolApproval,
                        )
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

            is MessagePartBlock.ContentBlock -> key(contentBlockKeys[blockIdx]) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        MessageSelectionContainer(enabled = role != MessageRole.ASSISTANT) {
                                if (role == MessageRole.USER) {
                                    // Graphite user bubble: solid userBubble fill, asymmetric
                                    // corners, no outline/stripe. 长按弹 menu sheet
                                    //（复制/重试/编辑/分享/...); 短按维持 edit 兼容旧行为.
                                    // DisableSelection 屏蔽 Android 系统的 text selection toolbar
                                    // (复制/全选/朗读/搜索) ——避免跟我们的 menu sheet 两个面板同时出现.
                                    androidx.compose.foundation.text.selection.DisableSelection {
                                    // Graphite §6.2 "User bubble": userBg fill, userInk text,
                                    // asymmetric radius 23/23/14/23 (topStart/topEnd/bottomEnd/
                                    // bottomStart), right-aligned, max-width ~92% of available.
                                    // The bottomEnd corner keeps the same 14dp radius as the
                                    // right-side prototype; the entire bubble stays softly round.
                                    val themeRadius = themeBubbleRadius()
                                    val userBubbleShape = themeRadius?.let {
                                        RoundedCornerShape(
                                            topStart = it,
                                            topEnd = it,
                                            bottomStart = it,
                                            bottomEnd = minOf(it, 6.dp),
                                        )
                                    } ?: androidx.compose.foundation.shape.RoundedCornerShape(
                                        topStart = 23.dp,
                                        topEnd = 23.dp,
                                        bottomEnd = 14.dp,
                                        bottomStart = 23.dp,
                                    )
                                    // §6.2 max-width = 92% of available width, right-aligned.
                                    // A plain layout modifier caps/places the bubble without a
                                    // nested subcomposition; see Modifier.chatBubbleWidth.
                                    Surface(
                                        modifier = Modifier
                                            .chatBubbleWidth(0.92f, alignEnd = true)
                                            .clip(userBubbleShape)
                                            .combinedClickable(
                                                onClick = { onUserMessageClick?.invoke() },
                                                onLongClick = { onUserMessageLongClick?.invoke() },
                                            ),
                                        shape = userBubbleShape,
                                        color = LocalAmberTokens.current.userBg,
                                        // 深底用户气泡 → 浅色文字 userInk（之前误用 ink 造成黑底黑字）
                                        contentColor = LocalAmberTokens.current.userInk,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = themeBubbleBorderWidth()?.takeIf { it > 0.dp }
                                            ?.let { BorderStroke(it, LocalAmberTokens.current.line) },
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            MarkdownBlock(
                                                content = MessageRenderCache.displayMarkdownText(
                                                    text = part.text,
                                                    regexes = regexes,
                                                    role = MessageRole.USER,
                                                ),
                                                fillWidth = false,
                                                onClickCitation = handleClickCitation
                                            )
                                        }
                                    }
                                    } // end DisableSelection
                                } else {
                                    // True only when this Text block is the very last block
                                    // in the message AND generation is ongoing. See lastBlockIdx
                                    // note above for why this is "is the trailing block" rather
                                    // than "is the last Text block".
                                    val isStreamingText = loading && blockIdx == lastBlockIdx
                                    val isSegmentedDelivery =
                                        settings.displaySetting.replyDeliveryMode == ReplyDeliveryMode.SEGMENTED_BLOCKS
                                    val appearedDuringLiveTurn = remember { loading }
                                    val assistantDisplayText = MessageRenderCache.displayMarkdownText(
                                        text = part.text,
                                        regexes = regexes,
                                        role = MessageRole.ASSISTANT,
                                    )
                                    var committedSegmentedLen by remember {
                                        mutableIntStateOf(if (isStreamingText) 0 else assistantDisplayText.length)
                                    }
                                    val visibleAssistantText = if (isSegmentedDelivery) {
                                        val committed = committedSegmentedReplyPrefix(
                                            text = assistantDisplayText,
                                            isStreaming = isStreamingText,
                                            previouslyCommittedLength = committedSegmentedLen,
                                        )
                                        if (committed.length > committedSegmentedLen) {
                                            committedSegmentedLen = committed.length
                                        }
                                        committed
                                    } else {
                                        assistantDisplayText
                                    }
                                    if (visibleAssistantText.isEmpty() && isStreamingText) {
                                        // Segmented mode holds back incomplete in-flight prose so
                                        // the single-line 3-dot wave status stays clean until a
                                        // complete block or full reply is ready to fade in.
                                    } else {
                                        // Streaming tail text block: single-Text markdown layer
                                        // (layout only grows, no block-tree re-layout mid-stream).
                                        // Disabled in segmented delivery mode so completed blocks
                                        // render cleanly via MarkdownBlock without word-by-word streaming.
                                        val presentationLifetime = rememberStreamingPresentationLifetime(
                                            streaming = isStreamingText && !isSegmentedDelivery,
                                            settledContent = visibleAssistantText,
                                        )
                                        val streamingMarkdownContent: (@Composable () -> Unit)? =
                                            if (!isSegmentedDelivery &&
                                                (isStreamingText || presentationLifetime.retained) &&
                                                !deferStreamingParse
                                            ) {
                                                {
                                                    presentationLifetime.MarkPresented()
                                                    StreamingSingleTextMarkdown(
                                                        text = part.text,
                                                        regexes = regexes,
                                                        streaming = isStreamingText,
                                                        onSettled = presentationLifetime.onDrained,
                                                    )
                                                }
                                            } else {
                                                null
                                            }
                                        val effectiveStreaming = isStreamingText && !isSegmentedDelivery
                                        if (settings.displaySetting.showAssistantBubble) {
                                            val amberTokens = LocalAmberTokens.current
                                            val themeRadius = themeBubbleRadius()
                                            val themeBorderWidth = themeBubbleBorderWidth()
                                            val assistantBubbleShape = RoundedCornerShape(themeRadius ?: 18.dp)
                                            Surface(
                                                modifier = Modifier
                                                    .chatBubbleWidth(0.90f)
                                                    .assistantBubbleEntrance(animate = appearedDuringLiveTurn),
                                                shape = assistantBubbleShape,
                                                color = amberTokens.surface,
                                                contentColor = amberTokens.ink,
                                                tonalElevation = 0.dp,
                                                shadowElevation = 0.dp,
                                                border = if (themeBorderWidth == null) {
                                                    BorderStroke(0.5.dp, amberTokens.line)
                                                } else {
                                                    themeBorderWidth.takeIf { it > 0.dp }
                                                        ?.let { BorderStroke(it, amberTokens.line) }
                                                },
                                            ) {
                                                Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                                                    AssistantMarkdownBlockOrWidgets(
                                                        content = visibleAssistantText,
                                                        streaming = effectiveStreaming,
                                                        deferStreamingParse = deferStreamingParse && effectiveStreaming,
                                                        streamingMarkdownContent = streamingMarkdownContent,
                                                        onClickCitation = handleClickCitation,
                                                        onGenerativeWidgetAction = onGenerativeWidgetAction,
                                                        onStreamingVisibleFrame = if (effectiveStreaming) {
                                                            onStreamingVisibleFrame
                                                        } else {
                                                            null
                                                        },
                                                        onStreamingVisualActiveChange = if (
                                                            effectiveStreaming || onStreamingVisibleFrame != null
                                                        ) {
                                                            onStreamingVisualActiveChange
                                                        } else {
                                                            null
                                                        },
                                                    )
                                                }
                                            }
                                        } else {
                                            AssistantMarkdownBlockOrWidgets(
                                                content = visibleAssistantText,
                                                modifier = Modifier.assistantBubbleEntrance(
                                                    animate = appearedDuringLiveTurn && isSegmentedDelivery,
                                                ),
                                                onClickCitation = handleClickCitation,
                                                streaming = effectiveStreaming,
                                                deferStreamingParse = deferStreamingParse && effectiveStreaming,
                                                streamingMarkdownContent = streamingMarkdownContent,
                                                onGenerativeWidgetAction = onGenerativeWidgetAction,
                                                onStreamingVisibleFrame = if (effectiveStreaming || onStreamingVisibleFrame != null) {
                                                    onStreamingVisibleFrame
                                                } else {
                                                    null
                                                },
                                                onStreamingVisualActiveChange = if (
                                                    effectiveStreaming || onStreamingVisibleFrame != null
                                                ) {
                                                    onStreamingVisualActiveChange
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    }
                                }
                        }
                    }

                    is UIMessagePart.Video -> {
                        // 卡片本身不解析视频：预留固定比例，点击才创建播放器，
                        // 关闭即停止。播放失败仍可走"用其他应用打开"。
                        var playing by remember(part.url) { mutableStateOf(false) }
                        val cover = rememberVideoCover(part.url)
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = { playing = true },
                            modifier = Modifier.widthIn(max = 260.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // 固定 44dp 缩略图：封面到位前后尺寸不变，timeline 不会因此跳一下。
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (cover != null) {
                                        Image(
                                            bitmap = cover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                        )
                                    }
                                    Icon(
                                        imageVector = Lucide.Play,
                                        contentDescription = null,
                                        tint = if (cover != null) {
                                            androidx.compose.ui.graphics.Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = part.url.substringAfterLast('/').ifBlank { "video" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (playing) {
                            VideoPlaybackDialog(
                                url = part.url,
                                onDismiss = { playing = false },
                            )
                        }
                    }

                    is UIMessagePart.Audio -> {
                        // Mirror the Document chip layout (icon + filename in a tonal pill)
                        // — previously this was just a bare music-note icon with no
                        // filename, which looked like a broken render rather than an
                        // attachment. Falls back to URL last-segment for old persisted
                        // Audio parts that don't carry fileName. Color-wise we use the
                        // workspace blue chip recipe (matches Select / Tag.INFO across
                        // the app) instead of Material `secondaryContainer`, which our
                        // theme tints peach-grey and reads as a generic "sender" colour.
                        val displayName = part.fileName.ifBlank {
                            part.url.toUri().lastPathSegment.orEmpty().ifBlank { "audio" }
                        }
                        val workspace = workspaceColors()
                        Surface(
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
                            // 配色用 chatTheme.accent 系列, 跟随当前聊天主题
                            color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accentSoft,
                            contentColor = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
                            border = BorderStroke(1.dp, app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent.copy(alpha = 0.12f)),
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Lucide.Music,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 120.dp, max = 216.dp)
                                    .widthIn(min = 120.dp, max = 360.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: chatTheme.surface)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .heightIn(max = 216.dp)
                                    .widthIn(max = 360.dp)
                            )
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                val uri = part.url.toUri()
                                intent.data = if (uri.scheme == "file") {
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        uri.toFile(),
                                    )
                                } else uri
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
                                                imageVector = Lucide.FileText,
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

                    is UIMessagePart.MiniApp -> {
                        MiniAppChatCard(
                            part = part,
                            onRun = { navController.navigate(Screen.MiniAppRunner(part.appId)) },
                            onOpenList = { navController.navigate(Screen.MiniAppList) },
                            onModify = onMiniAppModify,
                        )
                    }

                    is UIMessagePart.Reasoning,
                    is UIMessagePart.Tool -> Unit
                }
            }

            is MessagePartBlock.SubAgentBlock -> key("subagent-${block.step.runId}") {
                SubAgentTaskStepView(
                    step = block.step,
                    loading = loading,
                )
            }

            is MessagePartBlock.CouncilBlock -> key("council-${block.step.runId}") {
                CouncilTaskStepView(
                    step = block.step,
                    loading = loading,
                )
            }
        }
    }
}

/**
 * ContentBlock 的稳定 Compose key: 用"同类 part 在消息内的序号"代替原始 part 下标。
 *
 * 原始下标 (`block.index`) 在流式中会平移 —— 例如 ThinkTagTransformer 把 `<think>`
 * 提取成 Reasoning 插到 Text 之前时, Text 的 part 下标从 0 变 1, key 变化导致整块
 * Compose 状态 (选区/MarkdownBlock 内部 remember) 被销毁重建, 视觉上闪一下。
 * 同类序号在前面插入异类 part 时保持稳定。
 */
private fun List<MessagePartBlock>.stableContentBlockKeys(): List<String?> {
    val counters = HashMap<String, Int>()
    return map { block ->
        if (block !is MessagePartBlock.ContentBlock) return@map null
        val type = block.part::class.simpleName ?: "part"
        val ordinal = counters.merge(type, 1, Int::plus)!! - 1
        "content-$type-$ordinal"
    }
}

/**
 * Tool step 的稳定 Compose key: toolCallId 优先; blank id 时退到 provider 写入的
 * stream tool index (参数流式增长时不再每个 chunk 换 key); 两者都缺时才退回
 * hashCode (含 input, 每 chunk 都变, 仅作最后兜底)。
 */
private fun UIMessagePart.Tool.stableStepKey(): String =
    toolCallId.ifBlank {
        streamToolIndex()?.let { "stream-tool-$it" } ?: hashCode().toString()
    }

/**
 * Smooth whole-bubble fade-in + subtle upward glide when a newly generated assistant bubble
 * appears (aligned with iOS/HarmonyOS). Historical messages skip animation (`animate == false`).
 */
internal fun Modifier.assistantBubbleEntrance(animate: Boolean): Modifier = composed {
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate && progress.value < 1f) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            )
        }
    }
    this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = 6.dp.toPx() * (1f - p)
    }
}

/**
 * Segmented reply commit policy matching iOS `ChatSegmentedMarkdownCommitPolicy`:
 * - While streaming, only commits up to completed natural Markdown boundaries (closed code/math
 *   fences or blank-line-separated completed paragraphs/blocks). Incomplete trailing lines stay
 *   hidden behind the inline 3-dot wave status.
 * - Once streaming finishes (`isStreaming == false`), flushes the full text immediately.
 */
internal fun committedSegmentedReplyPrefix(
    text: String,
    isStreaming: Boolean,
    previouslyCommittedLength: Int = 0,
): String {
    if (!isStreaming) return text
    if (text.isEmpty()) return ""

    val safePrevious = previouslyCommittedLength.coerceIn(0, text.length)
    var fenceChar: Char? = null
    var fenceLen = 0
    var insideMathBlock = false
    var lastNonBlankContentEnd = 0
    var hasUncommittedBlockContent = false
    var committedEnd = 0

    var lineStart = 0
    while (lineStart < text.length) {
        var lineContentEnd = lineStart
        while (lineContentEnd < text.length && text[lineContentEnd] != '\n' && text[lineContentEnd] != '\r') {
            lineContentEnd++
        }
        val hasNewline = lineContentEnd < text.length
        if (!hasNewline) break

        var lineEnd = lineContentEnd + 1
        if (text[lineContentEnd] == '\r' && lineEnd < text.length && text[lineEnd] == '\n') {
            lineEnd++
        }
        val trimmed = text.substring(lineStart, lineContentEnd).trim()

        // Code fence tracking (``` or ~~~)
        if (!insideMathBlock) {
            val first = trimmed.firstOrNull()
            if (first == '`' || first == '~') {
                var run = 0
                while (run < trimmed.length && trimmed[run] == first) run++
                if (run >= 3) {
                    lastNonBlankContentEnd = lineContentEnd
                    hasUncommittedBlockContent = true
                    if (fenceChar == null) {
                        fenceChar = first
                        fenceLen = run
                    } else if (fenceChar == first && run >= fenceLen && trimmed.substring(run).isBlank()) {
                        fenceChar = null
                        fenceLen = 0
                        committedEnd = lineContentEnd
                        hasUncommittedBlockContent = false
                    }
                    lineStart = lineEnd
                    continue
                }
            }
            if (fenceChar != null) {
                lastNonBlankContentEnd = lineContentEnd
                hasUncommittedBlockContent = true
                lineStart = lineEnd
                continue
            }
        }

        // Display math block tracking ($$)
        if (trimmed.startsWith("$$")) {
            val singleLineMath = trimmed.length >= 4 && trimmed.endsWith("$$")
            lastNonBlankContentEnd = lineContentEnd
            hasUncommittedBlockContent = true
            if (!singleLineMath) {
                insideMathBlock = !insideMathBlock
            }
            if (!insideMathBlock) {
                committedEnd = lineContentEnd
                hasUncommittedBlockContent = false
            }
            lineStart = lineEnd
            continue
        }
        if (insideMathBlock) {
            lastNonBlankContentEnd = lineContentEnd
            hasUncommittedBlockContent = true
            lineStart = lineEnd
            continue
        }

        if (trimmed.isEmpty()) {
            if (hasUncommittedBlockContent) {
                committedEnd = lastNonBlankContentEnd
                hasUncommittedBlockContent = false
            }
        } else {
            lastNonBlankContentEnd = lineContentEnd
            hasUncommittedBlockContent = true
        }
        lineStart = lineEnd
    }

    val finalEnd = maxOf(committedEnd, safePrevious).coerceAtMost(text.length)
    return if (finalEnd == 0) "" else text.substring(0, finalEnd)
}
