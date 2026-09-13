package app.amber.feature.ui.components.message

import android.util.Log
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessagePart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Brain
import app.amber.agent.R
import app.amber.core.settings.resolveSessionDefaults
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import app.amber.feature.ui.components.richtext.StreamingPlainText
import app.amber.feature.ui.components.ui.ChainOfThoughtScope
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.modifier.shimmer
import app.amber.core.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private const val REASONING_PREVIEW_CHAR_LIMIT = 2_000
private const val REASONING_EXPANDED_STREAM_CHAR_LIMIT = 2_000
private const val REASONING_EXPANDED_FINAL_CHAR_LIMIT = 18_000

// 自动折叠相对"生成结束"的延迟: 错开结束瞬间的虚拟化切换/footer 显隐等布局变化
private const val REASONING_AUTO_COLLAPSE_DELAY_MS = 300L
private const val SCROLL_TAG = "AmberChatScroll"
private val REASONING_PREVIEW_HEIGHT = 100.dp
private val REASONING_STREAM_EXPANDED_MIN_HEIGHT = 220.dp
private val REASONING_STREAM_EXPANDED_MAX_HEIGHT = 320.dp
private val REASONING_FINAL_MAX_HEIGHT = 420.dp

// 思考预览框的自动跟随速度上限（dp/s）——playbook 坑14 的 pt 限速先例。
private const val REASONING_PREVIEW_FOLLOW_DP_PER_SECOND = 540f

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

internal fun reasoningCardStateAfterToggle(nextExpanded: Boolean): ReasoningCardState =
    if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed

internal fun shouldAutoCollapseReasoning(
    state: ReasoningCardState,
    loading: Boolean,
    autoCloseThinking: Boolean,
): Boolean = !loading && autoCloseThinking && state.expanded

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, _loading: Boolean) {
        // A user collapse is authoritative even while the model is still streaming. The old
        // Preview fallback made the next loading recomposition reopen the card immediately.
        expandState = reasoningCardStateAfterToggle(nextExpanded)
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

    LaunchedEffect(loading, messageLoading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
        } else {
            if (state.expandState.expanded) {
                if (shouldAutoCollapseReasoning(
                        state = state.expandState,
                        loading = loading,
                        autoCloseThinking = settings.displaySetting.autoCloseThinking,
                    )
                ) {
                    // 生成结束的同一帧里还会发生: 消息从整条 item 切换成虚拟化多
                    // item、ActionFooter 由占位转可见、流式/非流式渲染分支切换。
                    // 把自动折叠错开一拍, 让这些布局变化先落定, 避免叠加成一次
                    // 大跳变。loading 重新变 true (重新生成) 时此协程被取消。
                    Log.d(SCROLL_TAG, "[reasoning] auto-collapse will fire in ${REASONING_AUTO_COLLAPSE_DELAY_MS}ms")
                    delay(REASONING_AUTO_COLLAPSE_DELAY_MS)
                    Log.d(SCROLL_TAG, "[reasoning] auto-collapse COMMITTED → Collapsed")
                    state.expandState = ReasoningCardState.Collapsed
                } else {
                    state.expandState = ReasoningCardState.Expanded
                }
            }
        }
    }

    val previewDensity = LocalDensity.current.density
    LaunchedEffect(reasoning.reasoning.length, loading, state.expandState) {
        if (
            loading &&
            state.expandState.expanded &&
            !reasoning.reasoning.isReasoningTailTrimmed(
                loading = true,
                expanded = state.expandState == ReasoningCardState.Expanded,
            )
        ) {
            // Rate-limited continuous follow instead of per-append snap: the
            // preview box used to scrollTo(maxValue) on every length change,
            // jerking a full line height each append (STREAMING_PRESENTATION_
            // PLAYBOOK 坑14 — limit by pt/s, keep motion continuous). The user
            // reading the box can still win: touch steals the scroll.
            var lastNanos = 0L
            while (true) {
                val now = withFrameNanos { it }
                val frameMs = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1_000_000f).coerceIn(4f, 100f)
                lastNanos = now
                val remaining = scrollState.maxValue - scrollState.value
                if (remaining > 0) {
                    val step = minOf(
                        remaining.toFloat(),
                        REASONING_PREVIEW_FOLLOW_DP_PER_SECOND * previewDensity * frameMs / 1000f,
                    )
                    scrollState.dispatchRawDelta(step)
                }
            }
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
    regexes: List<AssistantRegex>,
    loading: Boolean,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
) {
    val workspace = workspaceColors()
    val isPreview = expandState == ReasoningCardState.Preview
    val omittedPrefixTemplate = stringResource(R.string.chat_message_reasoning_omitted)
    val displayText = remember(reasoning.reasoning, loading, expandState, omittedPrefixTemplate) {
        reasoning.reasoning.toDisplayReasoningText(
            loading = loading,
            expanded = expandState == ReasoningCardState.Expanded,
            omittedPrefixTemplate = omittedPrefixTemplate,
        )
    }
    // Streaming treatment only while the display text is the plain growing
    // prefix. Once toDisplayReasoningText switches to its sliding tail window
    // ("已省略前 N 字…"), every append rewrites the head — a prefix-breaking
    // content that would make the display buffer snap per chunk and reset the
    // reveal motion scope. Trimmed thoughts render statically, as before.
    val displayTextStreaming = remember(reasoning.reasoning, loading, expandState) {
        loading && !reasoning.reasoning.isReasoningTailTrimmed(
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
                        .heightIn(
                            min = REASONING_PREVIEW_HEIGHT,
                            max = REASONING_PREVIEW_HEIGHT,
                        )
                        .verticalScroll(scrollState)
                } else if (loading) {
                    contentModifier
                        .heightIn(
                            min = REASONING_STREAM_EXPANDED_MIN_HEIGHT,
                            max = REASONING_STREAM_EXPANDED_MAX_HEIGHT,
                        )
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                        .heightIn(max = REASONING_FINAL_MAX_HEIGHT)
                        .verticalScroll(scrollState)
                }
            }
    ) {
        SelectionContainer {
            // The enclosing card owns the symmetric body insets; no quote-rule indentation.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 思考是人的 prose，不是文档：小卡片里渲染 ## 大标题/粗体很出戏。
                // 显示层剥离标记（原文保留，导出/复制仍是 markdown），并复用与正文
                // 相同的词量化节奏 + 逐字淡入（StreamingPlainText）。
                StreamingPlainText(
                    text = MessageRenderCache.visualRegexText(
                        text = displayText,
                        regexes = regexes,
                        scope = AssistantAffectScope.ASSISTANT,
                    ),
                    // Thinking text streams like answer text: display-buffer
                    // pacing + tail reveal. Trimmed (sliding-window) thoughts
                    // are prefix-breaking content and render statically.
                    streaming = displayTextStreaming,
                    // Thoughts are human prose → SANS (.secondary), rendered directly on the
                    // thinking surface without a document-style quote rule.
                    style = LocalAmberType.current.secondary.copy(
                        color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.thinkBodyInk,
                        fontSize = 12.5.sp,
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
    regexes: List<AssistantRegex>,
    loading: Boolean,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
    framed: Boolean = true,
) {
    val (state, reasoningLoading) = rememberReasoningState(reasoning, loading)
    val preserveScrollAnchor = app.amber.feature.ui.context.LocalReasoningScrollAnchor.current
    var headerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

    val showReasoningDuration = reasoning.finishedAt != null || reasoningLoading
    val thinkingTitle = remember(reasoning.reasoning, reasoningLoading) {
        if (reasoningLoading) reasoning.reasoning.extractThinkingTitle() else null
    }
    val showThinkingTitle = thinkingTitle != null
    val workspace = workspaceColors()
    val settings = LocalSettings.current
    val reasoningLevel = model?.let { selectedModel ->
        settings.rememberedReasoningLevelsByModelId[selectedModel.id.toString()]
            ?: settings.resolveSessionDefaults(selectedModel).reasoningLevel
    }
    val reasoningLabel = reasoningLevel.reasoningLabel()

    // V3 主题感知 + 设计稿对齐: brain 图标 (替代默认灰圆豆 dot, 让"小图标"代表思考 step).
    val chatThemeForReasoning = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    ControlledChainOfThoughtStep(
        // Preview already has visible content; treat it as expanded for the control so a tap
        // collapses it instead of opening a second Expanded state first.
        expanded = state.expandState.expanded,
        onExpandedChange = { nextExpanded ->
            preserveScrollAnchor?.invoke {
                headerCoordinates?.takeIf { it.isAttached }?.positionInWindow()?.y
            }
            state.onExpandedChange(nextExpanded, reasoningLoading)
        },
        icon = {
            Icon(
                imageVector = Lucide.Brain,
                contentDescription = null,
                tint = chatThemeForReasoning.thinkHeaderInk,
                modifier = Modifier.size(12.dp),
            )
        },
        label = {
            androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned { headerCoordinates = it }) {
            if (thinkingTitle != null) {
                ReasoningTitle(title = thinkingTitle)
            } else {
                val baseText = if (showReasoningDuration) {
                    stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.displaySeconds(),
                    )
                } else {
                    stringResource(R.string.deep_thinking)
                }
                // 耗时与等级紧凑地显示在同一行。
                val combinedText = if (reasoningLabel != null) "$baseText · $reasoningLabel" else baseText
                // This line mixes human-readable Chinese with timing and mode. Keep it in the
                // sans UI face so CJK/Latin/digits stay compact and share a natural baseline.
                Text(
                    text = combinedText,
                    style = LocalAmberType.current.secondary.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.sp,
                    ),
                    color = chatThemeForReasoning.inkSoft,
                    modifier = Modifier.shimmer(isLoading = reasoningLoading),
                )
            }
            }
        },
        extra = {
            // V3 设计稿: 流式 title 时仅显示 duration 在右侧
            val durationLabel = if (showThinkingTitle && state.duration > 0.seconds) {
                "${state.duration.displaySeconds()}s"
            } else {
                null
            }
            if (durationLabel != null) {
                // Graphite §6.2: MONO duration, fainter than the header (the `Ns` machine fact).
                Text(
                    text = durationLabel,
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = chatThemeForReasoning.inkSoft,
                    modifier = Modifier.shimmer(isLoading = reasoningLoading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        // Framed reasoning uses symmetric body padding rather than a quote-rule inset.
        flushContent = false,
        framed = framed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                regexes = regexes,
                loading = reasoningLoading,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
            )
        },
    )
}

private fun Duration.displaySeconds(): Int =
    ceil(toDouble(DurationUnit.SECONDS)).coerceAtLeast(1.0).toInt()

@Composable
private fun ReasoningLevel?.reasoningLabel(): String? = when (this) {
    null,
    ReasoningLevel.OFF -> null
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

internal fun String.toDisplayReasoningText(
    loading: Boolean,
    expanded: Boolean,
    omittedPrefixTemplate: String = "… 已省略前 %1\$d 字，以保持流式思考界面流畅。",
): String {
    val limit = reasoningDisplayLimit(loading = loading, expanded = expanded)
    if (length <= limit) return this
    val omitted = length - limit
    return omittedPrefixTemplate.format(omitted) + "\n\n" + takeLast(limit)
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
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
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
            // Graphite §6.2: MONO header line for the streaming thinking title.
            style = LocalAmberType.current.meta.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Normal,
            ),
            // V3 主题感知 (Paper 砖红 / Plain 黑 / Midnight 靛蓝)
            color = chatTheme.inkSoft,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
