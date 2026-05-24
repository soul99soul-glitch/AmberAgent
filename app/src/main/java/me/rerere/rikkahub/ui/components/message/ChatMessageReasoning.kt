package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.resolveSessionDefaults
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private const val REASONING_PREVIEW_CHAR_LIMIT = 1_600
private const val REASONING_EXPANDED_STREAM_CHAR_LIMIT = 6_000
private const val REASONING_EXPANDED_FINAL_CHAR_LIMIT = 18_000

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(
    reasoning: UIMessagePart.Reasoning,
    messageLoading: Boolean,
): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val loading = messageLoading && reasoning.finishedAt == null
    val scrollState = rememberScrollState()
    val finishedAt = reasoning.finishedAt

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = when {
                finishedAt != null -> finishedAt - reasoning.createdAt
                loading -> Clock.System.now() - reasoning.createdAt
                else -> Duration.ZERO
            }
        )
    }

    LaunchedEffect(loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        }
    }

    LaunchedEffect(reasoning.reasoning.length, loading, state.expandState) {
        if (
            loading &&
            state.expandState.expanded &&
            !reasoning.reasoning.isReasoningTailTrimmed(
                loading = true,
                expanded = state.expandState == ReasoningCardState.Expanded,
            )
        ) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(250)
            }
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    loading: Boolean,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
) {
    val workspace = workspaceColors()
    val isPreview = expandState == ReasoningCardState.Preview
    val displayText = remember(reasoning.reasoning, loading, expandState) {
        reasoning.reasoning.toDisplayReasoningText(
            loading = loading,
            expanded = expandState == ReasoningCardState.Expanded,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        SelectionContainer {
            // V3 修: ReasoningContent 自带左竖线 — 让 reasoning step 有线、tool step 没线
            // (wrapper 不画). 线 X=0 (ReasoningContent 起始, 跟 flushContent=true 的 wrapper
            // step icon center 12dp 对齐), padding(start=14) 让文字距线 14dp.
            val thinkRuleColor = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current.thinkRule
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val strokePx = 2.dp.toPx()
                        drawLine(
                            color = thinkRuleColor,
                            start = Offset(strokePx / 2f, 0f),
                            end = Offset(strokePx / 2f, size.height),
                            strokeWidth = strokePx,
                        )
                    }
                    .padding(start = 14.dp),
            ) {
                MarkdownBlock(
                    content = MessageRenderCache.visualRegexText(
                        text = displayText,
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current.thinkBodyInk,
                        fontSize = 13.5.sp,
                        lineHeight = 23.sp,
                        letterSpacing = 0.2.sp,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    loading: Boolean,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, reasoningLoading) = rememberReasoningState(reasoning, loading)
    val showReasoningDuration = reasoning.finishedAt != null || reasoningLoading
    val thinkingTitle = remember(reasoning.reasoning, reasoningLoading) {
        if (reasoningLoading) reasoning.reasoning.extractThinkingTitle() else null
    }
    val showThinkingTitle = thinkingTitle != null
    val workspace = workspaceColors()
    val settings = LocalSettings.current
    val reasoningLevel = if (assistant != null && model != null) {
        settings.resolveSessionDefaults(assistant, model).reasoningLevel
    } else {
        assistant?.reasoningLevel
    }
    val budgetLabel = reasoningLevel.reasoningBudgetLabel()

    // V3 主题感知 + 设计稿对齐: brain 图标 (替代默认灰圆豆 dot, 让"小图标"代表思考 step) +
    //   flushContent=true 让 content 引用竖线 X 对齐 step icon center (12dp)
    val chatThemeForReasoning = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current
    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, reasoningLoading) },
        icon = {
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                tint = chatThemeForReasoning.thinkHeaderInk,
                modifier = Modifier.size(14.dp),
            )
        },
        label = {
            if (thinkingTitle != null) {
                ReasoningTitle(title = thinkingTitle)
            } else {
                val baseText = if (showReasoningDuration) {
                    stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    )
                } else {
                    stringResource(R.string.deep_thinking)
                }
                // V3 设计稿: "思考了 5.4 秒 · auto" 一体显示，不分 extra
                val combinedText = if (budgetLabel != null) "$baseText · $budgetLabel" else baseText
                Text(
                    text = combinedText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        // V3 设计稿: 头部 13sp + 字距 0.2
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.2.sp,
                    ),
                    color = chatThemeForReasoning.thinkHeaderInk,  // 鲜亮 accent，不再 0.72 alpha
                    modifier = Modifier.shimmer(isLoading = reasoningLoading),
                )
            }
        },
        extra = {
            // V3 设计稿: 流式 title 时仅显示 duration 在右侧
            val durationLabel = if (showThinkingTitle && state.duration > 0.seconds) {
                state.duration.toString(DurationUnit.SECONDS, 1)
            } else {
                null
            }
            if (durationLabel != null) {
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = chatThemeForReasoning.thinkHeaderInk.copy(alpha = 0.56f),
                    modifier = Modifier.shimmer(isLoading = reasoningLoading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        flushContent = true,  // V3: content 竖线 X 跟 step icon center 对齐
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                loading = reasoningLoading,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
            )
        },
    )
}

private fun ReasoningLevel?.reasoningBudgetLabel(): String? = when (this) {
    null,
    ReasoningLevel.OFF -> null
    ReasoningLevel.AUTO -> "auto"
    else -> "≤ ${this.budgetTokens.formatReasoningBudget()} tokens"
}

private fun Int.formatReasoningBudget(): String {
    return if (this >= 1_000) "${this / 1_000}K" else toString()
}

internal fun String.toDisplayReasoningText(
    loading: Boolean,
    expanded: Boolean,
): String {
    val limit = reasoningDisplayLimit(loading = loading, expanded = expanded)
    if (length <= limit) return this
    val omitted = length - limit
    return "… 已省略前 $omitted 字，以保持流式思考界面流畅。\n\n" + takeLast(limit)
}

internal fun String.isReasoningTailTrimmed(
    loading: Boolean,
    expanded: Boolean,
): Boolean = length > reasoningDisplayLimit(loading = loading, expanded = expanded)

private fun reasoningDisplayLimit(
    loading: Boolean,
    expanded: Boolean,
): Int {
    val limit = when {
        loading && expanded -> REASONING_EXPANDED_STREAM_CHAR_LIMIT
        loading -> REASONING_PREVIEW_CHAR_LIMIT
        expanded -> REASONING_EXPANDED_FINAL_CHAR_LIMIT
        else -> REASONING_PREVIEW_CHAR_LIMIT
    }
    return limit
}

@Composable
private fun ReasoningTitle(title: String) {
    val chatTheme = me.rerere.rikkahub.ui.pages.chat.LocalChatTheme.current
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
            ),
            // V3 主题感知 (Paper 砖红 / Plain 黑 / Midnight 靛蓝)
            color = chatTheme.thinkHeaderInk.copy(alpha = 0.72f),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
