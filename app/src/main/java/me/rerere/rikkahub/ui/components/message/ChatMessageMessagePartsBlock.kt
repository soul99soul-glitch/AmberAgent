package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.ai.generative.GenerativeUiPlanner
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetParser
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.miniapp.components.MiniAppChatCard
import kotlin.time.Duration.Companion.milliseconds

@Suppress("UNUSED_PARAMETER")
private fun Modifier.streamingContentSize(enabled: Boolean): Modifier = this

@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
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
    val navController = LocalNavController.current
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
                    // 2026-05-14: removed outer `Modifier.animateContentSizeIf(loading)` — the
                    // ChainOfThought card was the most visible jank source. Its inner
                    // `animateContentChanges = loading` still drives the step-list spring
                    // (less aggressive, single layer), so the user-visible "新思维步骤滑入"
                    // motion is preserved. Removing the outer Card-level spring is the
                    // free win — it stacked on top of the inner one with no added value.
                    ChainOfThought(
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
                                        loading = loading,
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
                                    // 2026-05-14: removed `.animateContentSizeIf(loading)` —
                                    // user messages don't change after send, so the loading
                                    // gate here was always inert except during the brief moment
                                    // before the assistant reply started. Cost > benefit.
                                    Surface(
                                        modifier = Modifier
                                            .widthIn(max = 560.dp),
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
                                                content = GenerativeUiPlanner.stripVisualRouteTagsForDisplay(
                                                    part.text.replaceRegexes(
                                                        assistant = assistant,
                                                        scope = AssistantAffectScope.USER,
                                                        visual = true,
                                                    )
                                                ),
                                                fillWidth = false,
                                                onClickCitation = handleClickCitation
                                            )
                                        }
                                    }
                                } else {
                                    // True only when this Text block is the very last block
                                    // in the message AND generation is ongoing. See lastBlockIdx
                                    // note above for why this is "is the trailing block" rather
                                    // than "is the last Text block".
                                    val isStreamingText = loading && blockIdx == lastBlockIdx
                                    val assistantDisplayText = part.text.replaceRegexes(
                                        assistant = assistant,
                                        scope = AssistantAffectScope.ASSISTANT,
                                        visual = true,
                                    )
                                    if (settings.displaySetting.showAssistantBubble) {
                                        // Streaming text height animation is intentionally disabled:
                                        // assistant markdown should grow by natural layout only, without
                                        // a restarted spring/tween on every accumulator flush.
                                        Surface(
                                            modifier = Modifier
                                                .widthIn(max = 640.dp)
                                                .streamingContentSize(isStreamingText),
                                            shape = RoundedCornerShape(8.dp),
                                            color = workspace.paper,
                                            contentColor = workspace.ink,
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                            border = BorderStroke(1.dp, workspace.hairline),
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                                                AssistantMarkdownBlockOrWidgets(
                                                    content = assistantDisplayText,
                                                    streaming = isStreamingText,
                                                    onClickCitation = handleClickCitation,
                                                    onGenerativeWidgetAction = onGenerativeWidgetAction,
                                                )
                                            }
                                        }
                                    } else {
                                        // Keep the no-bubble streaming path animation-free as well.
                                        AssistantMarkdownBlockOrWidgets(
                                            content = assistantDisplayText,
                                            modifier = Modifier.streamingContentSize(isStreamingText),
                                            onClickCitation = handleClickCitation,
                                            streaming = isStreamingText,
                                            onGenerativeWidgetAction = onGenerativeWidgetAction,
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
                            color = workspace.blueContainer.copy(alpha = 0.72f),
                            contentColor = workspace.blue,
                            border = BorderStroke(1.dp, workspace.blue.copy(alpha = 0.12f)),
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote01,
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
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 120.dp, max = 216.dp)
                                    .widthIn(min = 120.dp, max = 360.dp)
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

                    is UIMessagePart.MiniApp -> {
                        MiniAppChatCard(
                            part = part,
                            onRun = { navController.navigate(Screen.MiniAppRunner(part.appId)) },
                            onOpenList = { navController.navigate(Screen.MiniAppList) },
                        )
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

            is MessagePartBlock.CouncilBlock -> key("council-${block.step.runId}") {
                CouncilTaskStepView(
                    step = block.step,
                    loading = loading,
                )
            }
        }
    }

    MessageAnnotations(annotations = annotations, loading = loading)
}
