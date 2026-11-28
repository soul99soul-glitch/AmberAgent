package app.amber.feature.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import kotlinx.serialization.json.JsonElement

internal enum class CompactToolRunOverallState {
    RUNNING,
    WAITING_FOR_PERMISSION,
    SUCCEEDED,
    ATTENTION,
    FAILED,
}

internal data class CompactToolRunSummary(
    val activeStep: ThinkingStep.ToolStep,
    val activeStepIndex: Int,
    val totalSteps: Int,
    val completedSteps: Int,
    val overallState: CompactToolRunOverallState,
    val stepCountLabel: String,
    val subtitleHint: String?,
)

private val CompactCardPlatformTextStyle = PlatformTextStyle(includeFontPadding = false)
private val CompactCardLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/**
 * Collapses tool steps into a single in-place dynamically refreshing task card on the
 * Personal Agent stage (from step 1 onward so steps 2, 3, 4... update smoothly in place)
 * or whenever a thinking block carries 2+ tool calls on any stage.
 */
internal fun shouldUseCompactToolRunCard(
    steps: List<ThinkingStep>,
    isPersonalAgentStage: Boolean,
): Boolean {
    val toolStepCount = steps.count { it is ThinkingStep.ToolStep }
    if (toolStepCount == 0) return false
    return isPersonalAgentStage || toolStepCount >= 2
}

/**
 * Interactive tool cards (pending approvals, `ask_user` prompts, and `generate_image` visual
 * carousels) must always surface outside the collapsed task card so the user sees and acts on
 * them immediately without tapping to expand.
 */
internal fun UIMessagePart.Tool.requiresStandaloneInteractiveCard(
    themeTryOnCardTarget: ThemeTryOnCardTarget?,
): Boolean {
    if (toolName == ToolNames.ASK_USER) return true
    if (approvalState is ToolApprovalState.Pending) {
        return !matchesThemeTryOnCardTarget(themeTryOnCardTarget)
    }
    if (toolName == "generate_image" && approvalState !is ToolApprovalState.Denied) return true
    return false
}

internal fun extractToolStepSummaryHint(arguments: JsonElement): String? {
    val keys = listOf("goal", "query", "target", "package_name", "path", "url", "command")
    for (field in keys) {
        val value = arguments.getStringContent(field)?.trim()?.takeIf { it.isNotEmpty() }
        if (value != null) return value
    }
    return null
}

internal fun summarizeCompactToolRun(
    toolSteps: List<ThinkingStep.ToolStep>,
    loading: Boolean,
): CompactToolRunSummary {
    require(toolSteps.isNotEmpty())
    val statuses = toolSteps.map { step ->
        val content = if (step.tool.isExecuted) {
            MessageRenderCache.toolOutputJson(step.tool.output)
        } else {
            null
        }
        toolStatusFromMessagePart(
            tool = step.tool,
            loading = loading && !step.tool.isExecuted,
            content = content,
        )
    }

    val pendingIdx = statuses.indexOfLast { it == AgentToolStatus.WAITING_FOR_PERMISSION }
    val runningIdx = statuses.indexOfLast { it == AgentToolStatus.RUNNING }
    val activeIdx = when {
        pendingIdx >= 0 -> pendingIdx
        runningIdx >= 0 -> runningIdx
        else -> toolSteps.lastIndex
    }
    val activeStep = toolSteps[activeIdx]
    val activeStatus = statuses[activeIdx]
    val completedCount = statuses.count { it == AgentToolStatus.SUCCEEDED }
    val hasFailed = statuses.any { it == AgentToolStatus.FAILED || it == AgentToolStatus.TIMED_OUT }
    val hasAttention = statuses.any {
        it == AgentToolStatus.INTERRUPTED || it == AgentToolStatus.CANCELLED || it == AgentToolStatus.UNKNOWN
    }

    val overallState = when {
        pendingIdx >= 0 -> CompactToolRunOverallState.WAITING_FOR_PERMISSION
        runningIdx >= 0 -> CompactToolRunOverallState.RUNNING
        activeStatus == AgentToolStatus.FAILED || activeStatus == AgentToolStatus.TIMED_OUT || hasFailed ->
            CompactToolRunOverallState.FAILED
        hasAttention -> CompactToolRunOverallState.ATTENTION
        else -> CompactToolRunOverallState.SUCCEEDED
    }

    val total = toolSteps.size
    val stepCountLabel = when (overallState) {
        CompactToolRunOverallState.RUNNING -> "第 ${activeIdx + 1} 步"
        CompactToolRunOverallState.WAITING_FOR_PERMISSION -> "待授权 · 第 ${activeIdx + 1} 步"
        CompactToolRunOverallState.SUCCEEDED -> if (total == 1) "已完成" else "已完成 · $total 步"
        CompactToolRunOverallState.ATTENTION -> if (total == 1) "需关注" else "需关注 · $total 步"
        CompactToolRunOverallState.FAILED -> if (total == 1) "执行失败" else "失败 · $total 步"
    }

    val activeArgs = MessageRenderCache.toolInputJson(activeStep.tool.input)
    val hint = extractToolStepSummaryHint(activeArgs)

    return CompactToolRunSummary(
        activeStep = activeStep,
        activeStepIndex = activeIdx,
        totalSteps = total,
        completedSteps = completedCount,
        overallState = overallState,
        stepCountLabel = stepCountLabel,
        subtitleHint = hint,
    )
}

/**
 * Single warm Amber Duet task status card that refreshes in place across multi-step tool runs,
 * keeping collapsed height compact while allowing one-tap expansion to inspect every historical
 * step and detail sheet. Interactive steps (pending approvals, `ask_user`, `generate_image`)
 * are rendered independently right below the card so they are never hidden behind collapse.
 */
@Composable
internal fun CompactToolRunCard(
    toolSteps: List<ThinkingStep.ToolStep>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    themeTryOnCardTarget: ThemeTryOnCardTarget? = null,
    modifier: Modifier = Modifier,
) {
    if (toolSteps.isEmpty()) return

    val standaloneSteps = remember(toolSteps, themeTryOnCardTarget) {
        toolSteps.filter { it.tool.requiresStandaloneInteractiveCard(themeTryOnCardTarget) }
    }
    val standaloneKeys = remember(standaloneSteps) {
        standaloneSteps.mapTo(HashSet()) { it.tool.stableStepKey() }
    }
    val foldableSteps = remember(toolSteps, standaloneKeys) {
        toolSteps.filterNot { it.tool.stableStepKey() in standaloneKeys }
    }

    // If the entire block consists solely of standalone interactive step(s) (e.g. a single
    // ask_user prompt or single generate_image card), render them directly with no wrapper card.
    if (foldableSteps.isEmpty()) {
        ChainOfThought(
            steps = standaloneSteps,
            collapsedVisibleCount = Int.MAX_VALUE,
            animateContentChanges = false,
            drawTimeline = false,
            modifier = modifier,
        ) { step ->
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
        return
    }

    val tokens = LocalAmberTokens.current
    val amberType = LocalAmberType.current
    val chatTheme = LocalChatTheme.current
    var expanded by remember { mutableStateOf(false) }

    val summary = remember(toolSteps, loading) {
        summarizeCompactToolRun(toolSteps = toolSteps, loading = loading)
    }
    val activeTool = summary.activeStep.tool
    val activeArgs = remember(activeTool.input) { MessageRenderCache.toolInputJson(activeTool.input) }
    val activeMemoryAction = activeArgs.getStringContent("action")
    val primaryTitle = toolDisplayTitle(activeTool.toolName, activeArgs, activeMemoryAction)
    val primaryIcon = getToolIcon(activeTool.toolName, activeMemoryAction)

    // Resolve recent completed titles (up to 2) as a calm single-line secondary trail when
    // no explicit goal/query argument is available.
    val recentCompletedSteps = remember(toolSteps, summary.activeStepIndex) {
        toolSteps.withIndex()
            .filter { (idx, step) -> idx != summary.activeStepIndex && step.tool.isExecuted }
            .takeLast(2)
            .map { it.value }
    }
    val recentTitles = recentCompletedSteps.map { step ->
        val args = remember(step.tool.input) { MessageRenderCache.toolInputJson(step.tool.input) }
        toolDisplayTitle(step.tool.toolName, args, args.getStringContent("action"))
    }
    val subtitleLine = remember(summary.subtitleHint, recentTitles, summary.overallState, summary.totalSteps) {
        when {
            !summary.subtitleHint.isNullOrBlank() -> summary.subtitleHint
            recentTitles.isNotEmpty() -> "最近：${recentTitles.joinToString(" · ")}"
            summary.overallState == CompactToolRunOverallState.RUNNING -> "正在执行当前步骤…"
            summary.overallState == CompactToolRunOverallState.WAITING_FOR_PERMISSION -> "等待您的操作确认"
            summary.totalSteps > 1 -> "点击查看全部 ${summary.totalSteps} 步详情"
            else -> "点击查看执行详情"
        }
    }

    val isRunning = summary.overallState == CompactToolRunOverallState.RUNNING
    val statusAccentColor = when (summary.overallState) {
        CompactToolRunOverallState.RUNNING -> chatTheme.accent
        CompactToolRunOverallState.WAITING_FOR_PERMISSION -> chatTheme.accent
        CompactToolRunOverallState.SUCCEEDED -> if (tokens.isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
        CompactToolRunOverallState.ATTENTION -> if (tokens.isDark) Color(0xFFFFB74D) else Color(0xFF9A5B13)
        CompactToolRunOverallState.FAILED -> if (tokens.isDark) Color(0xFFEF5350) else Color(0xFFB3261E)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compact-tool-run-card"),
            shape = RoundedCornerShape(16.dp),
            color = tokens.surface,
            contentColor = tokens.ink,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(
                width = 0.75.dp,
                color = if (isRunning) chatTheme.accent.copy(alpha = 0.34f) else tokens.line,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .testTag("compact-tool-run-card-header")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) {
                                    chatTheme.accent.copy(alpha = 0.12f)
                                } else {
                                    tokens.raised
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = primaryIcon,
                            contentDescription = null,
                            tint = if (isRunning) chatTheme.accent else statusAccentColor,
                            modifier = Modifier.size(15.dp),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        AnimatedContent(
                            targetState = primaryTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp),
                            contentAlignment = Alignment.CenterStart,
                            transitionSpec = {
                                (
                                    fadeIn(tween(170, easing = FastOutSlowInEasing)) togetherWith
                                        fadeOut(tween(120, easing = FastOutSlowInEasing))
                                ).using(null)
                            },
                            label = "compact_tool_card_title",
                        ) { titleText ->
                            Text(
                                text = titleText,
                                style = amberType.body.copy(
                                    fontSize = 13.5.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    platformStyle = CompactCardPlatformTextStyle,
                                    lineHeightStyle = CompactCardLineHeightStyle,
                                ),
                                color = tokens.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        AnimatedContent(
                            targetState = subtitleLine,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(17.dp),
                            contentAlignment = Alignment.CenterStart,
                            transitionSpec = {
                                (
                                    fadeIn(tween(170, easing = FastOutSlowInEasing)) togetherWith
                                        fadeOut(tween(120, easing = FastOutSlowInEasing))
                                ).using(null)
                            },
                            label = "compact_tool_card_subtitle",
                        ) { subText ->
                            Text(
                                text = subText,
                                style = amberType.secondary.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    platformStyle = CompactCardPlatformTextStyle,
                                    lineHeightStyle = CompactCardLineHeightStyle,
                                ),
                                color = tokens.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        when (summary.overallState) {
                            CompactToolRunOverallState.RUNNING -> CompactToolBreathingDot(color = statusAccentColor)
                            CompactToolRunOverallState.WAITING_FOR_PERMISSION -> Icon(
                                imageVector = Lucide.Clock,
                                contentDescription = null,
                                tint = statusAccentColor,
                                modifier = Modifier.size(13.dp),
                            )
                            CompactToolRunOverallState.SUCCEEDED -> Icon(
                                imageVector = Lucide.Check,
                                contentDescription = null,
                                tint = statusAccentColor,
                                modifier = Modifier.size(13.dp),
                            )
                            CompactToolRunOverallState.ATTENTION,
                            CompactToolRunOverallState.FAILED -> Icon(
                                imageVector = Lucide.CircleAlert,
                                contentDescription = null,
                                tint = statusAccentColor,
                                modifier = Modifier.size(13.dp),
                            )
                        }

                        Text(
                            text = summary.stepCountLabel,
                            style = amberType.meta.copy(
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                fontWeight = FontWeight.Medium,
                                platformStyle = CompactCardPlatformTextStyle,
                                lineHeightStyle = CompactCardLineHeightStyle,
                            ),
                            color = if (isRunning) chatTheme.accent else tokens.ink2,
                            maxLines = 1,
                            modifier = Modifier.testTag("compact-tool-run-card-count"),
                        )

                        Icon(
                            imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compact-tool-run-card-steps"),
                    ) {
                        HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            ChainOfThought(
                                steps = foldableSteps,
                                collapsedVisibleCount = Int.MAX_VALUE,
                                animateContentChanges = false,
                                drawTimeline = false,
                            ) { step ->
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
                    }
                }
            }
        }

        // Render interactive steps (pending approvals, ask_user, generate_image) outside the
        // collapsed card so they remain immediately visible and actionable.
        if (standaloneSteps.isNotEmpty()) {
            ChainOfThought(
                steps = standaloneSteps,
                collapsedVisibleCount = Int.MAX_VALUE,
                animateContentChanges = false,
                drawTimeline = false,
                modifier = Modifier.testTag("compact-tool-run-standalone-steps"),
            ) { step ->
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
    }
}

@Composable
private fun CompactToolBreathingDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "compact_tool_breathing_dot")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_pulse",
    )
    Box(
        modifier = modifier
            .size(7.dp)
            .graphicsLayer {
                alpha = pulse
                val scale = 0.86f + 0.22f * pulse
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(color),
    )
}
