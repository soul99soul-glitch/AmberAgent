package app.amber.feature.ui.components.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.UIMessagePart
import app.amber.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.workspaceColors
import org.koin.compose.koinInject
import java.io.File

/**
 * Render a coalesced subagent task: one card replaces the multiple subagent_* tool calls
 * (start / wait / read / cancel) that share a run_id.
 *
 * Status is derived from the parsed `status` field of the latest wait/read output (most
 * authoritative view of the subagent's actual state); falls back to RUNNING if no result
 * has arrived yet. While running, cycles through the role's [phaseLabels] every few seconds
 * to give a sense of progression. Phase 5 wires the click to an expandable run sheet.
 */
@Composable
fun SubAgentTaskStepView(
    step: ThinkingStep.SubAgentTaskStep,
    loading: Boolean,
) {
    var showSheet by remember(step.runId) { mutableStateOf(false) }
    val anchor = step.anchor
    val arguments = remember(anchor.input) { MessageRenderCache.toolInputJson(anchor.input) }
    val subagentId = arguments.getStringContent("subagent_id") ?: "subagent"
    val def = remember(subagentId) { SubAgentDefinitions.find(subagentId) }
    val customName = arguments.jsonObjectOrNull
        ?.payloadObject("custom_subagent")
        ?.getStringContent("name")
        ?.takeIf { it.isNotBlank() }
    val displayName = extractLatestSubAgentName(step.tools)
        ?: customName
        ?: def?.name
        ?: subagentId

    // coalesceSubAgentSteps rebuilds step.tools each render even when contents are identical,
    // so remember(step.tools) wouldn't actually memoize — drop the wrap; the parse is cheap.
    val parsedStatus = parseLatestSubAgentStatus(step.tools)
    val isRunning = parsedStatus == SubAgentRunStatus.RUNNING

    val phaseLabels = def?.phaseLabels.orEmpty()
    // Reset cycle when role's phaseLabels list changes (mid-run edits to a custom role would
    // otherwise leave phaseIndex pointing past the new list's end).
    var phaseIndex by remember(step.runId, phaseLabels) { mutableIntStateOf(0) }
    LaunchedEffect(step.runId, isRunning, phaseLabels.size) {
        if (!isRunning || phaseLabels.size <= 1) return@LaunchedEffect
        while (true) {
            delay(PHASE_LABEL_INTERVAL_MS)
            phaseIndex = (phaseIndex + 1) % phaseLabels.size
        }
    }
    val currentPhase = when {
        phaseLabels.isEmpty() -> ""
        isRunning -> phaseLabels[phaseIndex.coerceIn(phaseLabels.indices)]
        else -> phaseLabels.last()
    }

    val statusVerb = when (parsedStatus) {
        SubAgentRunStatus.RUNNING -> "正在工作"
        SubAgentRunStatus.COMPLETED -> "已完成"
        SubAgentRunStatus.FAILED -> "失败"
        SubAgentRunStatus.CANCELLED -> "已取消"
        SubAgentRunStatus.TIMED_OUT -> "超时"
        SubAgentRunStatus.APPROVAL_REQUIRED -> "等待审批"
        SubAgentRunStatus.INTERRUPTED -> "已中断"
    }
    val title = if (isRunning && currentPhase.isNotBlank()) {
        "@$displayName $statusVerb · $currentPhase"
    } else {
        "@$displayName $statusVerb"
    }

    AgentToolCallCapsule(
        title = title,
        toolName = "subagent_task",
        icon = HugeIcons.MagicWand01,
        kind = AgentToolKind.GENERIC,
        status = parsedStatus.toAgentToolStatus(),
        loading = loading && isRunning,
        onClick = { showSheet = true },
        approvalActions = null,
    )

    if (showSheet) {
        // Sheet derives its own status — sheet may outlive the outer card's recomposition
        // (e.g. card scrolled offscreen in a LazyColumn while sheet is still open), so we
        // can't rely on parent-passed verb staying fresh.
        SubAgentRunSheet(
            step = step,
            displayName = displayName,
            onDismiss = { showSheet = false },
        )
    }
}

internal const val PHASE_LABEL_INTERVAL_MS = 5_000L

/**
 * Walk the subagent_* tools in reverse order and pick the most recent parsable `status` from
 * the result payload. Returns RUNNING if no result has been observed yet (initial state).
 */
private fun parseLatestSubAgentStatus(tools: List<UIMessagePart.Tool>): SubAgentRunStatus {
    for (tool in tools.asReversed()) {
        val statusStr = tool.cachedSubAgentOutputJsonObject()?.get("status")
            ?.let { it as? JsonPrimitive }?.contentOrNull ?: continue
        SubAgentRunStatus.entries.firstOrNull { it.name.equals(statusStr, ignoreCase = true) }
            ?.let { return it }
    }
    return SubAgentRunStatus.RUNNING
}

private fun extractLatestSubAgentName(tools: List<UIMessagePart.Tool>): String? {
    for (tool in tools.asReversed()) {
        val parsed = tool.cachedSubAgentOutputJsonObject() ?: continue
        val name = parsed.getStringContent("subagent_name")
        if (!name.isNullOrBlank()) return name
    }
    return null
}

private fun UIMessagePart.Tool.cachedSubAgentOutputJsonObject(): JsonObject? =
    MessageRenderCache.toolOutputJson(output) as? JsonObject

private fun SubAgentRunStatus.toAgentToolStatus(): AgentToolStatus = when (this) {
    SubAgentRunStatus.RUNNING -> AgentToolStatus.RUNNING
    SubAgentRunStatus.APPROVAL_REQUIRED -> AgentToolStatus.WAITING_FOR_PERMISSION
    SubAgentRunStatus.COMPLETED -> AgentToolStatus.SUCCEEDED
    SubAgentRunStatus.FAILED,
    SubAgentRunStatus.TIMED_OUT,
    SubAgentRunStatus.INTERRUPTED -> AgentToolStatus.FAILED
    SubAgentRunStatus.CANCELLED -> AgentToolStatus.CANCELLED
}

/**
 * Bottom sheet showing the subagent's full task spec and live streaming output. Subscribes to
 * [SubAgentManager.liveTextFlow] so the body updates token-by-token as the run progresses.
 *
 * Fallback: if the live flow is empty (e.g. app was killed and the conversation reopened later,
 * so the manager no longer holds a flow for this run), pull the final summary out of the latest
 * subagent_wait/read tool result. Best-effort — if neither has anything we just show "等待输出".
 */
@Composable
private fun SubAgentRunSheet(
    step: ThinkingStep.SubAgentTaskStep,
    displayName: String,
    onDismiss: () -> Unit,
) {
    val manager: SubAgentManager = koinInject()
    val context = LocalContext.current
    val workspace = workspaceColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val anchor = step.anchor
    val arguments = remember(anchor.input) { MessageRenderCache.toolInputJson(anchor.input) }
    val taskObjective = remember(arguments) {
        runCatching {
            arguments.jsonObject["task"]?.jsonObject?.get("objective")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // Re-derive status here (instead of receiving from parent) so the sheet stays correct even
    // when the outer card is offscreen and not recomposing.
    val parsedStatus = parseLatestSubAgentStatus(step.tools)
    val isRunning = parsedStatus == SubAgentRunStatus.RUNNING
    val statusVerb = when (parsedStatus) {
        SubAgentRunStatus.RUNNING -> "正在工作"
        SubAgentRunStatus.COMPLETED -> "已完成"
        SubAgentRunStatus.FAILED -> "失败"
        SubAgentRunStatus.CANCELLED -> "已取消"
        SubAgentRunStatus.TIMED_OUT -> "超时"
        SubAgentRunStatus.APPROVAL_REQUIRED -> "等待审批"
        SubAgentRunStatus.INTERRUPTED -> "已中断"
    }

    // Live flow may be null when the manager has no record of this run (process restart, eviction).
    // In that case create an empty fallback flow so collectAsState works.
    val liveFlow = remember(step.runId) { manager.liveTextFlow(step.runId) ?: MutableStateFlow("") }
    val liveText by liveFlow.collectAsState()
    val snapshotRun = manager.snapshot(step.runId)
    val snapshotText = snapshotRun?.displayText.orEmpty()
    var transcriptText by remember(step.runId) { mutableStateOf("") }
    val finalText = extractFinalSubAgentText(step.tools)
    LaunchedEffect(step.runId, parsedStatus, liveText, snapshotText) {
        val mayBeStaleRunningAfterRestart = isRunning && snapshotRun == null
        if ((!isRunning || mayBeStaleRunningAfterRestart) &&
            liveText.isBlank() &&
            snapshotText.isBlank() &&
            transcriptText.isBlank()
        ) {
            transcriptText = extractSubAgentDisplayTextFromTranscript(
                tools = step.tools,
                runRoot = File(context.filesDir, "amberagent/subagents/runs"),
            )
        }
    }
    val displayText = liveText.ifBlank { snapshotText.ifBlank { transcriptText.ifBlank { finalText } } }

    val scrollState = rememberScrollState()
    var followBottom by remember(step.runId) { mutableStateOf(true) }
    LaunchedEffect(scrollState, isRunning) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (value, maxValue) ->
                if (isRunning && value >= (maxValue - 8).coerceAtLeast(0)) {
                    followBottom = true
                }
            }
    }
    // Auto-follow only while the run is active. Once it finishes the user can scroll up freely
    // without being yanked back to the bottom.
    //
    // Scroll spec mirrors ChatList.scrollToTimelineBottom (tween 80ms + LinearEasing).
    // Default spring on ScrollState.animateScrollTo holds isScrollInProgress true for
    // ~1s after the destination is reached, which gates off the next chunk's scroll
    // — same shape of bug as 1.8.9's main-chat scroll regression.
    LaunchedEffect(displayText, isRunning, followBottom) {
        if (isRunning && followBottom) {
            scrollState.animateScrollTo(
                value = scrollState.maxValue,
                animationSpec = tween(durationMillis = 80, easing = LinearEasing),
            )
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .pointerInput(isRunning) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (isRunning) followBottom = false
                    }
                }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = HugeIcons.MagicWand01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = "@$displayName",
                    style = MaterialTheme.typography.titleMedium,
                    color = workspace.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = statusVerb,
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
            }

            taskObjective?.takeIf { it.isNotBlank() }?.let { objective ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = workspace.row,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "任务",
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.faint,
                        )
                        Text(
                            text = objective,
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.ink,
                        )
                    }
                }
            }

            HorizontalDivider(color = workspace.hairline)

            // Live / final text body — render as Markdown so headings, bold, lists, code,
            // hashtags-as-text etc. all show properly. Falls back to plain "waiting" text when
            // nothing has streamed in yet.
            if (displayText.isBlank()) {
                Text(
                    text = "等待输出...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                SelectionContainer {
                    MarkdownBlock(
                        content = displayText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                        streaming = isRunning,
                    )
                }
            }
        }
    }
}
