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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.resolveSessionDefaults
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
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
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent)
                state.expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
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
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
) {
    val workspace = workspaceColors()
    val isPreview = expandState == ReasoningCardState.Preview
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
            // Pulse reasoning panel: ink spotlight surface with chartreuse text.
            // Reasoning is the agent's internal monologue — visually it should
            // feel like peering into the engine, not a normal message bubble.
            // Mapping to Pulse semantic slots:
            //   tertiaryContainer  → ink-soft (the spotlight surface family)
            //   onTertiaryContainer→ chartreuse (high-contrast on dark)
            // No border — the inversion of palette already separates it from
            // surrounding content; an extra hairline reads as visual noise.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                MarkdownBlock(
                    content = reasoning.reasoning.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        visual = true,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val thinkingTitle = remember(reasoning.reasoning, loading) {
        if (loading) reasoning.reasoning.extractThinkingTitle() else null
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

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading) },
        icon = {
            // Brain icon: chartreuse-tinted at 80% to read as "live thinking"
            // without going full-saturation. Keep below 1.0 alpha so it doesn't
            // out-shout the message body that follows after collapse.
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
            )
        },
        label = {
            if (thinkingTitle != null) {
                ReasoningTitle(title = thinkingTitle)
            } else {
                // "Deep thinking · X.Xs" — chartreuse primary at 80% alpha so it
                // tracks the brain icon's tone consistently.
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            val durationLabel = if (showThinkingTitle && state.duration > 0.seconds) {
                state.duration.toString(DurationUnit.SECONDS, 1)
            } else {
                null
            }
            val metaText = listOfNotNull(durationLabel, budgetLabel).joinToString(" · ")
            if (metaText.isNotBlank()) {
                // Meta line (duration · budget): warm grey instead of dimmed
                // chartreuse. Two stacked chartreuse-tinted lines would read
                // as visual noise; the meta line steps back into onSurfaceVariant
                // so the eye prioritises the active label.
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
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


@Composable
private fun ReasoningTitle(title: String) {
    val workspace = workspaceColors()
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
            color = workspace.blue.copy(alpha = 0.72f),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
