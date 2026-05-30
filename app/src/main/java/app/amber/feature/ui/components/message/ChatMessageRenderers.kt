package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.core.ai.generative.GenerativeWidgetSegment
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.MarkdownTopLevelBlock
import app.amber.feature.ui.components.richtext.topLevelBlockCount
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.JetbrainsMono

@Composable
internal fun VirtualizedAssistantText(
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
                            content = MessageRenderCache.visualRegexText(
                                text = part.text,
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                            ),
                            streaming = false,
                            onClickCitation = handleClickCitation,
                            onGenerativeWidgetAction = onGenerativeWidgetAction,
                        )
                    }
                }
            } else {
                AssistantMarkdownBlockOrWidgets(
                    content = MessageRenderCache.visualRegexText(
                        text = part.text,
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
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
internal fun AssistantMarkdownBlockOrWidgets(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean,
    deferStreamingParse: Boolean = false,
    onClickCitation: (String) -> Unit,
    onGenerativeWidgetAction: (String) -> Unit,
    onStreamingVisibleFrame: (() -> Unit)? = null,
) {
    val generativeUiEnabled = LocalSettings.current.agentRuntime.generativeUi.enabled
    val canRenderWidget = remember(generativeUiEnabled, content) {
        generativeUiEnabled &&
            (GenerativeWidgetParser.containsWidgetFence(content) ||
                GenerativeWidgetParser.containsFullHtmlDeckPayload(content))
    }

    if (!canRenderWidget && content.looksLikeMiniAppJsonPayload()) {
        MiniAppJsonPreview(
            content = content,
            streaming = streaming,
            modifier = modifier,
        )
        return
    }

    if (!canRenderWidget) {
        MarkdownBlock(
            content = content,
            modifier = modifier,
            // Forward streaming so the tail block of the assistant response fades in
            // smoothly on each 200ms flush instead of popping. See MarkdownBlock kdoc.
            streaming = streaming,
            deferStreamingParse = deferStreamingParse,
            onStreamingVisibleFrame = onStreamingVisibleFrame,
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
        val lastSegmentIdx = segments.lastIndex
        segments.forEachIndexed { index, segment ->
            key(index) {
                // Only the trailing segment is still receiving new tokens during
                // streaming — earlier Text segments have already been frozen by the
                // widget parser (a closed fence ended them). So only forward
                // streaming=true to the tail; earlier blocks render statically.
                // Without this guard each accumulator flush would re-fade every
                // historical Text segment on every recompose, which reads as the
                // whole reply "blinking" instead of just the tail growing.
                val segmentStreaming = streaming && index == lastSegmentIdx
                when (segment) {
                    is GenerativeWidgetSegment.Text -> MarkdownBlock(
                        content = segment.content,
                        streaming = segmentStreaming,
                        deferStreamingParse = deferStreamingParse && segmentStreaming,
                        onStreamingVisibleFrame = if (segmentStreaming) {
                            onStreamingVisibleFrame
                        } else {
                            null
                        },
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
private fun MiniAppJsonPreview(
    content: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val previewText = remember(content) {
        content.trim().removeSurroundingMiniAppFence()
    }

    LaunchedEffect(previewText.length, streaming) {
        if (streaming) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(8.dp),
        color = chatTheme.widgetCanvas.takeIf { it.isSpecified } ?: chatTheme.surface,
        contentColor = chatTheme.inkSoft,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (streaming) "正在生成小应用" else "MiniApp JSON",
                style = MaterialTheme.typography.labelMedium,
                color = chatTheme.accent,
            )
            Text(
                text = previewText,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                color = chatTheme.inkSoft,
            )
        }
    }
}

private fun String.looksLikeMiniAppJsonPayload(): Boolean {
    val text = trimStart()
    if (!text.startsWith("{") && !text.startsWith("```")) return false

    var score = 0
    if ("\"title\"" in text) score++
    if ("\"description\"" in text) score++
    if ("\"html\"" in text || "<html" in text || "<!DOCTYPE html" in text) score += 2
    if ("\"permissions\"" in text) score++
    if ("\"category\"" in text) score++
    return score >= 3
}

private fun String.removeSurroundingMiniAppFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return trimmed
    val firstLineEnd = trimmed.indexOf('\n')
    if (firstLineEnd < 0) return trimmed.removePrefix("```").trimStart()
    val body = trimmed.substring(firstLineEnd + 1)
    val endFence = body.lastIndexOf("```")
    return if (endFence >= 0) body.substring(0, endFence).trim() else body.trim()
}

@Composable
internal fun ReasoningWidgetRescue(
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
