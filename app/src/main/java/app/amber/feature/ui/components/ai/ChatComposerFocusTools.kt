package app.amber.feature.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import app.amber.feature.ui.components.ds.AmberGlassRole
import app.amber.feature.ui.components.ds.amberGlass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.amber.agent.R
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.registry.ModelRegistry
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ContextFootprintEstimator
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Package
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

internal enum class ComposerFocusPopover {
    THINKING,
    CONTEXT,
}

private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000

/**
 * Real context usage snapshot for the composer context ring & popover, aligned with iOS
 * `ChatContextSnapshot`: zero fabricated percentages or quota placeholders.
 */
@Immutable
internal data class ComposerContextSnapshot(
    val messageCount: Int,
    val usedTokens: Int,
    val windowTokens: Int,
    val lastTurnPromptTokens: Int? = null,
    val lastTurnCompletionTokens: Int? = null,
    val lastTurnCachedTokens: Int? = null,
    val lastTurnElapsedMs: Long? = null,
    val hasModel: Boolean = false,
) {
    val resolvedWindowTokens: Int
        get() = windowTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS

    val fillFraction: Float
        get() {
            val ceiling = resolvedWindowTokens
            if (ceiling <= 0 || usedTokens <= 0) return 0f
            return (usedTokens.toFloat() / ceiling.toFloat()).coerceIn(0f, 1f)
        }

    /** Rounded to 0.5% steps so streaming updates do not churn animations on tiny deltas. */
    val displayedFillFraction: Float
        get() = ((fillFraction * 200f).roundToInt() / 200f).coerceIn(0f, 1f)

    val occupancyText: String
        get() = "${formatComposerTokenCount(usedTokens)} / ${formatComposerTokenCount(resolvedWindowTokens)}"

    val cacheHitRateText: String
        get() {
            val prompt = lastTurnPromptTokens ?: 0
            val cached = lastTurnCachedTokens ?: 0
            if (prompt <= 0 || cached <= 0) return "—"
            val pct = ((cached.toDouble() / prompt.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
            return "$pct%"
        }

    val tokensPerSecond: Double?
        get() {
            val completion = lastTurnCompletionTokens ?: 0
            val elapsedMs = lastTurnElapsedMs ?: 0L
            if (completion <= 0 || elapsedMs <= 0L) return null
            val tps = completion.toDouble() / (elapsedMs.toDouble() / 1000.0)
            return tps.takeIf { it.isFinite() && it > 0.0 }
        }
}

internal fun formatComposerTokenCount(tokens: Int): String {
    val clamped = tokens.coerceAtLeast(0)
    return when {
        clamped >= 1_000_000 -> {
            val m = clamped / 1_000_000.0
            if (clamped % 1_000_000 == 0) "${clamped / 1_000_000}M" else String.format(java.util.Locale.US, "%.1fM", m)
        }
        clamped >= 10_000 -> "${(clamped + 500) / 1000}K"
        clamped >= 1_000 -> {
            val k = clamped / 1_000.0
            if (clamped % 1_000 == 0) "${clamped / 1_000}K" else String.format(java.util.Locale.US, "%.1fK", k)
        }
        else -> clamped.toString()
    }
}

internal fun resolveComposerReasoningState(
    model: Model?,
    currentLevel: ReasoningLevel,
): ComposerReasoningUiState {
    if (model == null) {
        return ComposerReasoningUiState(
            hasModel = false,
            available = false,
            selectedLevel = ReasoningLevel.OFF,
            levels = emptyList(),
        )
    }
    val supportsReasoning = ModelAbility.REASONING in model.abilities
    if (!supportsReasoning) {
        return ComposerReasoningUiState(
            hasModel = true,
            available = false,
            selectedLevel = ReasoningLevel.OFF,
            levels = emptyList(),
        )
    }
    val levels = reasoningLevelsForModel(model).map { it.first }.distinct()
    val hasAdjustableLevels = levels.any { it != ReasoningLevel.OFF }
    return ComposerReasoningUiState(
        hasModel = true,
        available = hasAdjustableLevels,
        selectedLevel = if (hasAdjustableLevels) currentLevel else ReasoningLevel.OFF,
        levels = if (hasAdjustableLevels) levels else emptyList(),
    )
}

@Immutable
internal data class ComposerReasoningUiState(
    val hasModel: Boolean,
    val available: Boolean,
    val selectedLevel: ReasoningLevel,
    val levels: List<ReasoningLevel>,
) {
    val isActiveLevel: Boolean
        get() = available && selectedLevel != ReasoningLevel.OFF
}

@Composable
internal fun rememberComposerContextSnapshot(
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    currentChatModel: Model?,
): ComposerContextSnapshot {
    val activeMessages = remember(conversation.messageNodes) {
        conversation.messageNodes.mapNotNull { it.messages.getOrNull(it.selectIndex) }
    }
    val lastAssistant = remember(activeMessages) {
        activeMessages.asReversed().firstOrNull { message ->
            message.role == MessageRole.ASSISTANT && message.usage?.let { usage ->
                usage.promptTokens > 0 || usage.completionTokens > 0 || usage.cachedTokens > 0
            } == true
        }
    }
    val usage = lastAssistant?.usage
    val measuredPromptTokens = usage?.promptTokens?.takeIf { it > 0 }
    val cache = remember(conversation.id) { ContextFootprintEstimator.ConversationInputTokenCache() }
    val estimatedInputTokens by produceState(
        initialValue = 0,
        conversation.id,
        conversation.messageNodes.size,
        activeMessages.lastOrNull()?.id,
        contextCompacts.size,
        measuredPromptTokens,
    ) {
        value = if (activeMessages.isEmpty() || measuredPromptTokens != null) {
            0
        } else {
            withContext(Dispatchers.Default) {
                cache.estimateConversationInputTokens(conversation, contextCompacts)
            }
        }
    }
    val usedTokens = measuredPromptTokens ?: estimatedInputTokens
    val contextWindowTokens = currentChatModel?.let { model ->
        ModelRegistry.MODEL_CONTEXT_WINDOW.getData(model.modelId) ?: model.contextWindowTokens
    } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val elapsedMs = lastAssistant?.finishedAt?.let { finished ->
        val zone = TimeZone.currentSystemDefault()
        (finished.toInstant(zone).toEpochMilliseconds() -
            lastAssistant.createdAt.toInstant(zone).toEpochMilliseconds()).takeIf { it > 0L }
    }
    return ComposerContextSnapshot(
        messageCount = activeMessages.size,
        usedTokens = usedTokens,
        windowTokens = contextWindowTokens,
        lastTurnPromptTokens = usage?.promptTokens?.takeIf { it > 0 },
        lastTurnCompletionTokens = usage?.completionTokens?.takeIf { it > 0 },
        lastTurnCachedTokens = usage?.cachedTokens?.takeIf { it > 0 },
        lastTurnElapsedMs = elapsedMs,
        hasModel = currentChatModel != null,
    )
}

internal val ComposerFocusToolVisualHeight = 34.dp
internal val ComposerFocusToolTouchHeight = 44.dp

/**
 * Focused composer bottom row aligned with iOS:
 * - Left: model picker capsule
 * - Right: floating thinking-level button + floating context-ring button with anchored popovers
 */
@Composable
internal fun ComposerFocusToolsRow(
    model: Model?,
    onOpenModelPicker: () -> Unit,
    reasoningState: ComposerReasoningUiState,
    onSelectReasoningLevel: (Uuid, ReasoningLevel) -> Unit,
    contextSnapshot: ComposerContextSnapshot,
    compactLifecycleState: CompactLifecycleState,
    onCompactContext: () -> Unit,
    activePopover: ComposerFocusPopover?,
    onActivePopoverChange: (ComposerFocusPopover?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = activePopover != null) {
        onActivePopoverChange(null)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .heightIn(min = ComposerFocusToolTouchHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            ChatComposerModelButton(
                model = model,
                onClick = {
                    onActivePopoverChange(null)
                    onOpenModelPicker()
                },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ComposerThinkingButton(
                model = model,
                reasoningState = reasoningState,
                expanded = activePopover == ComposerFocusPopover.THINKING,
                onToggle = {
                    onActivePopoverChange(
                        if (activePopover == ComposerFocusPopover.THINKING) null
                        else ComposerFocusPopover.THINKING,
                    )
                },
                onDismiss = {
                    if (activePopover == ComposerFocusPopover.THINKING) onActivePopoverChange(null)
                },
                onSelectLevel = { level ->
                    model?.let { onSelectReasoningLevel(it.id, level) }
                    onActivePopoverChange(null)
                },
            )

            ComposerContextRingButton(
                snapshot = contextSnapshot,
                compactLifecycleState = compactLifecycleState,
                expanded = activePopover == ComposerFocusPopover.CONTEXT,
                onToggle = {
                    onActivePopoverChange(
                        if (activePopover == ComposerFocusPopover.CONTEXT) null
                        else ComposerFocusPopover.CONTEXT,
                    )
                },
                onDismiss = {
                    if (activePopover == ComposerFocusPopover.CONTEXT) onActivePopoverChange(null)
                },
                onCompactContext = {
                    onActivePopoverChange(null)
                    onCompactContext()
                },
            )
        }
    }
}

@Composable
private fun ComposerThinkingButton(
    model: Model?,
    reasoningState: ComposerReasoningUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSelectLevel: (ReasoningLevel) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val a11yLabel = stringResource(R.string.chat_input_reasoning_level)
    val tint = when {
        expanded || reasoningState.isActiveLevel -> tokens.accentLabel
        reasoningState.available -> tokens.ink2
        else -> tokens.ink3
    }

    val interactionSource = remember { MutableInteractionSource() }
    val indication = androidx.compose.foundation.LocalIndication.current
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(ComposerFocusToolTouchHeight)
                .testTag("chat-composer-thinking-button")
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics { contentDescription = a11yLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(ComposerFocusToolVisualHeight)
                    .testTag("chat-composer-thinking-visual")
                    .amberGlass(CircleShape, AmberGlassRole.Chip, highlighted = expanded)
                    .indication(interactionSource, indication),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Brain,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (expanded) {
            ComposerAnchoredPopover(
                onDismiss = onDismiss,
                modifier = Modifier.testTag("chat-composer-thinking-popover"),
            ) {
                ComposerThinkingPopoverContent(
                    model = model,
                    reasoningState = reasoningState,
                    onSelectLevel = onSelectLevel,
                )
            }
        }
    }
}

@Composable
internal fun ComposerThinkingPopoverContent(
    model: Model?,
    reasoningState: ComposerReasoningUiState,
    onSelectLevel: (ReasoningLevel) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    if (!reasoningState.available || model == null) {
        Column(
            modifier = Modifier
                .width(204.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.composer_thinking_unavailable_title),
                style = type.secondary.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = tokens.ink,
            )
            Text(
                text = stringResource(
                    if (!reasoningState.hasModel) R.string.composer_thinking_no_model_desc
                    else R.string.composer_thinking_unsupported_desc,
                ),
                style = type.meta.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = tokens.ink3,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .width(184.dp)
                .padding(vertical = 4.dp),
        ) {
            reasoningState.levels.forEachIndexed { index, level ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = tokens.line,
                    )
                }
                val selected = level == reasoningState.selectedLevel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("chat-composer-thinking-option-${level.name.lowercase()}")
                        .clickable(role = Role.RadioButton) { onSelectLevel(level) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = reasoningLevelDisplayLabel(level),
                        style = type.secondary.copy(
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) tokens.accentLabel else tokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Lucide.Check,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerContextRingButton(
    snapshot: ComposerContextSnapshot,
    compactLifecycleState: CompactLifecycleState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onCompactContext: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val compacting = compactLifecycleState.isActive
    val a11yLabel = if (compacting) {
        stringResource(R.string.composer_context_compacting)
    } else {
        "${stringResource(R.string.composer_context_title)}, ${snapshot.occupancyText}"
    }
    val animatedFraction by animateFloatAsState(
        targetValue = snapshot.displayedFillFraction,
        animationSpec = tween(durationMillis = 260),
        label = "composerContextRingFill",
    )
    val rotation = if (compacting) {
        val transition = rememberInfiniteTransition(label = "composerContextCompacting")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "composerContextRingSpin",
        ).value
    } else {
        0f
    }
    val trackColor = lerp(tokens.accent, tokens.surface, if (tokens.isDark) 0.72f else 0.80f)

    val interactionSource = remember { MutableInteractionSource() }
    val indication = androidx.compose.foundation.LocalIndication.current
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(ComposerFocusToolTouchHeight)
                .testTag("chat-composer-context-button")
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onToggle,
                )
                .semantics { contentDescription = a11yLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(ComposerFocusToolVisualHeight)
                    .testTag("chat-composer-context-visual")
                    .amberGlass(CircleShape, AmberGlassRole.Chip, highlighted = expanded)
                    .indication(interactionSource, indication),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val strokePx = 2.6.dp.toPx()
                        val inset = strokePx / 2f
                        val arcSize = Size(size.width - strokePx, size.height - strokePx)
                        if (compacting) {
                            drawArc(
                                color = tokens.accent.copy(alpha = 0.22f),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                            )
                            drawArc(
                                color = tokens.accent,
                                startAngle = rotation - 90f,
                                sweepAngle = 250f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                            )
                        } else {
                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = strokePx, cap = StrokeCap.Round),
                            )
                            if (animatedFraction > 0.001f) {
                                drawArc(
                                    color = tokens.accent,
                                    startAngle = -90f,
                                    sweepAngle = (animatedFraction * 360f).coerceAtLeast(8f),
                                    useCenter = false,
                                    topLeft = Offset(inset, inset),
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                    if (compacting) {
                        Icon(
                            imageVector = Lucide.Package,
                            contentDescription = null,
                            tint = tokens.accent,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }
        }

        if (expanded) {
            ComposerAnchoredPopover(
                onDismiss = onDismiss,
                modifier = Modifier.testTag("chat-composer-context-popover"),
            ) {
                ComposerContextPopoverContent(
                    snapshot = snapshot,
                    compacting = compacting,
                    onCompactContext = onCompactContext,
                )
            }
        }
    }
}

@Composable
internal fun ComposerContextPopoverContent(
    snapshot: ComposerContextSnapshot,
    compacting: Boolean,
    onCompactContext: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val trackColor = if (tokens.isDark) tokens.surface2 else lerp(tokens.surface2, tokens.line, 0.35f)
    val speedText = snapshot.tokensPerSecond?.let {
        String.format(java.util.Locale.US, "%.1f tok/s", it)
    } ?: stringResource(R.string.composer_context_speed_empty)

    Column(
        modifier = Modifier
            .width(256.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(52.dp)) {
                    val strokePx = 7.dp.toPx()
                    val inset = strokePx / 2f
                    val arcSize = Size(size.width - strokePx, size.height - strokePx)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    if (snapshot.fillFraction > 0.001f) {
                        drawArc(
                            color = tokens.accent,
                            startAngle = -90f,
                            sweepAngle = (snapshot.fillFraction * 360f).coerceAtLeast(8f),
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ContextStatRow(
                    label = stringResource(R.string.composer_context_messages),
                    value = snapshot.messageCount.toString(),
                )
                ContextStatRow(
                    label = stringResource(R.string.composer_context_window),
                    value = snapshot.occupancyText,
                )
                ContextStatRow(
                    label = stringResource(R.string.composer_context_speed),
                    value = speedText,
                )
                ContextStatRow(
                    label = stringResource(R.string.composer_context_cache_hit),
                    value = snapshot.cacheHitRateText,
                )
            }
        }

        if (compacting) {
            HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            Text(
                text = stringResource(R.string.composer_context_compacting),
                style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = tokens.accentLabel,
            )
        } else if (snapshot.messageCount > 0) {
            HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            Surface(
                onClick = onCompactContext,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .testTag("chat-composer-context-compact"),
                shape = RoundedCornerShape(10.dp),
                color = tokens.surface,
                contentColor = tokens.ink,
                border = BorderStroke(0.5.dp, tokens.floatingOutline),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.composer_context_compact_action),
                        style = type.secondary.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                        color = tokens.ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextStatRow(label: String, value: String) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = type.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = type.meta.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = tokens.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComposerAnchoredPopover(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }
    val padXPx = with(density) { 16.dp.roundToPx() }
    val padYPx = with(density) { 20.dp.roundToPx() }
    val positionProvider = remember(gapPx, marginPx, padXPx, padYPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // The popup carries a transparent inset for the shadow; place the visible panel.
                val maxX = (windowSize.width - popupContentSize.width - marginPx + padXPx).coerceAtLeast(marginPx - padXPx)
                val x = (anchorBounds.right - popupContentSize.width + padXPx).coerceIn(marginPx - padXPx, maxX)
                val aboveY = anchorBounds.top - popupContentSize.height - gapPx + padYPx
                val belowY = anchorBounds.bottom + gapPx - padYPx
                val y = if (aboveY + padYPx >= marginPx) {
                    aboveY
                } else {
                    belowY.coerceAtMost((windowSize.height - popupContentSize.height - marginPx + padYPx).coerceAtLeast(marginPx - padYPx))
                }
                return IntOffset(x, y)
            }
        }
    }
    val shape = RoundedCornerShape(16.dp)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            // Keep focus on the composer TextField so the IME stays continuously open
            // while toggling Thinking / Context popovers. Back dismissal is handled by BackHandler.
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
        ),
    ) {
        // Inset so the soft panel shadow is not cut by the popup window bounds.
        Box(Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            Surface(
                modifier = modifier.amberGlass(shape, AmberGlassRole.Panel),
                shape = shape,
                color = Color.Transparent,
                contentColor = tokens.ink,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun reasoningLevelDisplayLabel(level: ReasoningLevel): String = stringResource(
    when (level) {
        ReasoningLevel.OFF -> R.string.reasoning_off
        ReasoningLevel.AUTO -> R.string.reasoning_auto
        ReasoningLevel.LOW -> R.string.reasoning_light
        ReasoningLevel.MEDIUM -> R.string.reasoning_medium
        ReasoningLevel.HIGH -> R.string.reasoning_heavy
        ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
        ReasoningLevel.MAX -> R.string.reasoning_max
    },
)
