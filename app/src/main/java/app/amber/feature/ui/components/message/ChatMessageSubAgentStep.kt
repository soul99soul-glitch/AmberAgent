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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.common.http.jsonObjectOrNull
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WandSparkles
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.subagent.AppSubAgentDisplayLocalizer
import org.koin.compose.koinInject
import java.io.File

/**
 * Render a coalesced subagent task: one card replaces the multiple subagent_* tool calls
 * (start / wait / read / cancel / followup / send / interrupt) that share a
 * thread id.
 *
 * Status is derived from the latest lifecycle result and the manager's live
 * thread state; operation receipts remain visibly pending until a turn result
 * exists. While running, cycles through the role's [phaseLabels] every few
 * seconds to give a sense of progression. Phase 5 wires the click to an
 * expandable run sheet.
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
    val context = LocalContext.current
    val localeTag = context.resources.configuration.locales.toLanguageTags()
    val displayLocalizer = remember(localeTag) { AppSubAgentDisplayLocalizer(context) }
    val display = def?.let(displayLocalizer::localize)
    val customName = arguments.jsonObjectOrNull
        ?.payloadObject("custom_subagent")
        ?.getStringContent("name")
        ?.takeIf { it.isNotBlank() }
    val displayName = display?.name
        ?: extractLatestSubAgentName(step.tools)
        ?: customName
        ?: subagentId

    // coalesceSubAgentSteps rebuilds step.tools each render even when contents are identical,
    // so remember(step.tools) wouldn't actually memoize — drop the wrap; the parse is cheap.
    val cardState = deriveSubAgentCardState(step.tools)
    val parsedStatus = cardState.status

    // P4-02: after a restart the conversation may only contain subagent_start
    // (the parent never got to wait/read), so the tool-derived status would
    // stay RUNNING forever while the thread has actually finished. When the
    // persisted thread graph knows a terminal status, prefer it.
    val manager: SubAgentManager = koinInject()
    val observedRunFlow = remember(step.runId) { manager.runStateFlow(step.runId) }
    val observedRunState = observedRunFlow.collectAsState(initial = null)
    val observedRun = observedRunState.value
    var persistedState by remember(step.runId) {
        mutableStateOf<app.amber.feature.subagent.ThreadGraphManager.ThreadGraphState?>(null)
    }
    LaunchedEffect(step.runId) {
        persistedState = manager.persistedState(step.runId)
            ?.takeIf { it.status.isTerminalForDisplay() }
    }
    val persistedStatus = persistedState?.status
    val persistedIsCurrentTurn = cardState.latestUpdatedAtMs?.let { latest ->
        persistedState?.updatedAtMs?.let { it >= latest }
    } == true
    val timelineStatus = if (parsedStatus == SubAgentRunStatus.RUNNING && observedRun != null) {
        observedRun.status
    } else {
        parsedStatus
    }
    val effectiveStatus = when {
        cardState.canUsePersistedStatus && persistedIsCurrentTurn &&
            timelineStatus == SubAgentRunStatus.RUNNING && persistedStatus != null -> persistedStatus
        else -> timelineStatus
    }
    val isRunning = cardState.isCurrentTurnActive(effectiveStatus)

    val phaseLabels = display?.phaseLabels.orEmpty()
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

    val statusVerb = cardState.localizedStatusVerb(effectiveStatus)
    val title = if (isRunning && currentPhase.isNotBlank() && !cardState.isAccepted && !cardState.isDelivered) {
        "@$displayName $statusVerb · $currentPhase"
    } else {
        "@$displayName $statusVerb"
    }

    // Accepted/queued/delivered are operation receipts, not task completion.
    // Keep the existing capsule status vocabulary, but render these states as
    // pending so the check badge never claims that send_message completed the
    // subagent task.
    val capsuleStatus = if (cardState.isPendingReceipt) {
        AgentToolStatus.RUNNING
    } else {
        effectiveStatus.toAgentToolStatus()
    }

    AgentToolCallCapsule(
        title = title,
        toolName = "subagent_task",
        icon = Lucide.WandSparkles,
        kind = AgentToolKind.GENERIC,
        status = capsuleStatus,
        loading = loading && isRunning,
        onClick = { showSheet = true },
        approvalActions = null,
        leadingContent = {
            SubAgentAvatar(
                id = subagentId,
                name = displayName,
                avatarSize = 16.dp,
                status = effectiveStatus,
            )
        },
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

/** P4-02: terminal statuses the UI may take from the persisted thread graph. */
private fun SubAgentRunStatus.isTerminalForDisplay(): Boolean = when (this) {
    SubAgentRunStatus.COMPLETED,
    SubAgentRunStatus.FAILED,
    SubAgentRunStatus.CANCELLED,
    SubAgentRunStatus.TIMED_OUT,
    SubAgentRunStatus.INTERRUPTED,
    -> true

    SubAgentRunStatus.RUNNING,
    SubAgentRunStatus.APPROVAL_REQUIRED,
    -> false
}

/** The operation represented by the latest subagent tool call in a task card. */
internal enum class SubAgentCardOperation {
    START,
    WAIT,
    READ,
    CANCEL,
    FOLLOWUP,
    SEND_MESSAGE,
    INTERRUPT,
}

/**
 * UI projection of the subagent tool timeline. [status] is the latest actual
 * run status; receipt fields describe a newer followup/message operation and
 * therefore must not be mistaken for a completed run.
 */
internal data class SubAgentCardState(
    val status: SubAgentRunStatus,
    val turn: Int,
    val latestOperation: SubAgentCardOperation?,
    val deliveryState: String?,
    val latestUpdatedAtMs: Long?,
    val requestPending: Boolean,
) {
    val isQueued: Boolean
        get() = latestOperation == SubAgentCardOperation.SEND_MESSAGE &&
            deliveryState == "queued"

    val isDelivered: Boolean
        get() = latestOperation == SubAgentCardOperation.SEND_MESSAGE &&
            deliveryState == "delivered"

    val isAccepted: Boolean
        get() = requestPending && latestOperation in setOf(
            SubAgentCardOperation.FOLLOWUP,
            SubAgentCardOperation.SEND_MESSAGE,
            SubAgentCardOperation.INTERRUPT,
        )

    /** Operation receipts use a pending visual state even when an older turn completed. */
    val isPendingReceipt: Boolean
        get() = isQueued || isAccepted || isDelivered

    /** A timestamp lets cold-start reads distinguish this turn from an older result. */
    val canUsePersistedStatus: Boolean
        get() = status == SubAgentRunStatus.RUNNING && latestUpdatedAtMs != null

    fun isCurrentTurnActive(effectiveStatus: SubAgentRunStatus): Boolean =
        !isQueued && (isAccepted || isDelivered || effectiveStatus == SubAgentRunStatus.RUNNING)

    fun statusVerb(effectiveStatus: SubAgentRunStatus): String {
        if (isQueued) return "补充已排队"
        if (isAccepted) {
            return if (latestOperation == SubAgentCardOperation.FOLLOWUP) {
                "第${turn}轮已接受"
            } else {
                "请求已接受"
            }
        }
        if (isDelivered) return "补充已送达"
        val prefix = if (turn > 1) "第${turn}轮 " else ""
        return prefix + when (effectiveStatus) {
            SubAgentRunStatus.RUNNING -> "正在工作"
            SubAgentRunStatus.COMPLETED -> "已完成"
            SubAgentRunStatus.FAILED -> "失败"
            SubAgentRunStatus.CANCELLED -> "已取消"
            SubAgentRunStatus.TIMED_OUT -> "超时"
            SubAgentRunStatus.APPROVAL_REQUIRED -> "等待审批"
            SubAgentRunStatus.INTERRUPTED -> "已中断"
        }
    }
}

@Composable
private fun SubAgentCardState.localizedStatusVerb(effectiveStatus: SubAgentRunStatus): String {
    if (isQueued || isAccepted || isDelivered) return statusVerb(effectiveStatus)
    val prefix = if (turn > 1) "第${turn}轮 " else ""
    val localizedStatus = when (effectiveStatus) {
        SubAgentRunStatus.RUNNING -> stringResource(R.string.chat_message_subagent_status_working)
        SubAgentRunStatus.COMPLETED -> stringResource(R.string.chat_message_subagent_status_completed)
        SubAgentRunStatus.FAILED -> stringResource(R.string.chat_message_subagent_status_failed)
        SubAgentRunStatus.CANCELLED -> stringResource(R.string.chat_message_subagent_status_cancelled)
        SubAgentRunStatus.TIMED_OUT -> stringResource(R.string.chat_message_subagent_status_timed_out)
        SubAgentRunStatus.APPROVAL_REQUIRED -> stringResource(R.string.chat_message_subagent_status_waiting_approval)
        SubAgentRunStatus.INTERRUPTED -> stringResource(R.string.chat_message_subagent_status_interrupted)
    }
    return prefix + localizedStatus
}

/**
 * Fold the operation receipts in chronological order. A queued send retains
 * the previous terminal status in [SubAgentCardState.status], while its card
 * remains pending until a later followup/result is recorded.
 */
internal fun deriveSubAgentCardState(tools: List<UIMessagePart.Tool>): SubAgentCardState {
    var status = SubAgentRunStatus.RUNNING
    var turn = 1
    var latestOperation: SubAgentCardOperation? = null
    var deliveryState: String? = null
    var latestUpdatedAtMs: Long? = null
    var requestPending = false

    tools.forEach { tool ->
        val operation = tool.toolName.toSubAgentCardOperation() ?: return@forEach
        val payload = tool.cachedSubAgentOutputJsonObject()
        val reportedStatus = payload
            ?.get("status")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.let { raw ->
                SubAgentRunStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }

        latestOperation = operation
        deliveryState = null
        latestUpdatedAtMs = payload
            ?.get("updated_at_ms")
            ?.let { it as? JsonPrimitive }
            ?.longOrNull
        requestPending = false

        when (operation) {
            SubAgentCardOperation.FOLLOWUP -> {
                turn += 1
                if (reportedStatus != null) {
                    status = reportedStatus
                } else {
                    status = SubAgentRunStatus.RUNNING
                    requestPending = !tool.isExecuted
                }
            }

            SubAgentCardOperation.SEND_MESSAGE -> {
                deliveryState = payload
                    ?.get("delivery_state")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                    ?.lowercase()
                when {
                    deliveryState == "delivered" -> status = SubAgentRunStatus.RUNNING
                    reportedStatus != null -> status = reportedStatus
                    deliveryState == null -> requestPending = !tool.isExecuted
                }
            }

            SubAgentCardOperation.INTERRUPT -> {
                if (reportedStatus != null) {
                    status = reportedStatus
                } else {
                    requestPending = !tool.isExecuted
                }
            }

            SubAgentCardOperation.START,
            SubAgentCardOperation.WAIT,
            SubAgentCardOperation.READ,
            SubAgentCardOperation.CANCEL,
            -> {
                if (reportedStatus != null) status = reportedStatus
            }
        }
    }

    return SubAgentCardState(
        status = status,
        turn = turn,
        latestOperation = latestOperation,
        deliveryState = deliveryState,
        latestUpdatedAtMs = latestUpdatedAtMs,
        requestPending = requestPending,
    )
}

private fun String.toSubAgentCardOperation(): SubAgentCardOperation? = when (this) {
    "subagent_start" -> SubAgentCardOperation.START
    "subagent_wait" -> SubAgentCardOperation.WAIT
    "subagent_read" -> SubAgentCardOperation.READ
    "subagent_cancel" -> SubAgentCardOperation.CANCEL
    "subagent_followup" -> SubAgentCardOperation.FOLLOWUP
    "subagent_send_message" -> SubAgentCardOperation.SEND_MESSAGE
    "subagent_interrupt" -> SubAgentCardOperation.INTERRUPT
    else -> null
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
    val subagentId = arguments.getStringContent("subagent_id") ?: "subagent"
    val taskObjective = remember(arguments) {
        runCatching {
            arguments.jsonObject["task"]?.jsonObject?.get("objective")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    // Re-derive status here (instead of receiving from parent) so the sheet stays correct even
    // when the outer card is offscreen and not recomposing.
    val cardState = deriveSubAgentCardState(step.tools)
    val parsedStatus = cardState.status
    val observedRunFlow = remember(step.runId) { manager.runStateFlow(step.runId) }
    val observedRunState = observedRunFlow.collectAsState(initial = null)
    val observedRun = observedRunState.value
    var persistedState by remember(step.runId) {
        mutableStateOf<app.amber.feature.subagent.ThreadGraphManager.ThreadGraphState?>(null)
    }
    LaunchedEffect(step.runId) {
        persistedState = manager.persistedState(step.runId)
            ?.takeIf { it.status.isTerminalForDisplay() }
    }
    val persistedStatus = persistedState?.status
    val persistedIsCurrentTurn = cardState.latestUpdatedAtMs?.let { latest ->
        persistedState?.updatedAtMs?.let { it >= latest }
    } == true
    val timelineStatus = if (parsedStatus == SubAgentRunStatus.RUNNING && observedRun != null) {
        observedRun.status
    } else {
        parsedStatus
    }
    val effectiveStatus = if (
        cardState.canUsePersistedStatus &&
        persistedIsCurrentTurn &&
        timelineStatus == SubAgentRunStatus.RUNNING &&
        persistedStatus != null
    ) {
        persistedStatus!!
    } else {
        timelineStatus
    }
    val isRunning = cardState.isCurrentTurnActive(effectiveStatus)
    val statusVerb = cardState.localizedStatusVerb(effectiveStatus)

    // Live flow may be null when the manager has no record of this run (process restart, eviction).
    // In that case create an empty fallback flow so collectAsState works.
    // The flow can be created after this sheet opens when a restored thread receives a followup;
    // key the lookup by the manager's generation timestamp so the sheet adopts that live stream.
    val liveFlow = remember(step.runId, observedRun?.updatedAtMs) {
        manager.liveTextFlow(step.runId) ?: MutableStateFlow("")
    }
    val livePartsFlow = remember(step.runId, observedRun?.updatedAtMs) {
        manager.livePartsFlow(step.runId) ?: MutableStateFlow<List<UIMessagePart>>(emptyList())
    }
    val liveText by liveFlow.collectAsState()
    val liveParts by livePartsFlow.collectAsState()
    val searchPresentation = rememberSearchPresentation(liveParts)
    val searchSources = searchPresentation.sources.takeIf { it.isNotEmpty }
    val snapshotRun = manager.snapshot(step.runId)
    val snapshotText = snapshotRun?.displayText.orEmpty()
    var transcriptText by remember(step.runId) { mutableStateOf("") }
    // P4-02: child final answer from the persisted thread graph — shown after
    // a restart when neither the live flow nor the transcript is available.
    var persistedAnswer by remember(step.runId) { mutableStateOf("") }
    val finalText = extractFinalSubAgentText(step.tools)
    LaunchedEffect(step.runId, effectiveStatus, liveText, snapshotText) {
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
        if (mayBeStaleRunningAfterRestart) {
            persistedAnswer = manager.persistedState(step.runId)?.finalAnswer.orEmpty()
        }
    }
    val displayText = liveText.ifBlank { snapshotText.ifBlank { transcriptText.ifBlank { finalText.ifBlank { persistedAnswer } } } }

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
                SubAgentAvatar(
                    id = subagentId,
                    name = displayName,
                    avatarSize = 22.dp,
                    status = effectiveStatus,
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
                            text = stringResource(R.string.chat_message_subagent_task_label),
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

            SearchImageGallery(
                images = searchPresentation.images,
                modifier = Modifier.fillMaxWidth(),
            )

            // Live / final text body — render as Markdown so headings, bold, lists, code,
            // hashtags-as-text etc. all show properly. Falls back to plain "waiting" text when
            // nothing has streamed in yet.
            if (displayText.isBlank()) {
                Text(
                    text = stringResource(R.string.chat_message_subagent_waiting_output),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.faint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                SelectionContainer {
                    CompositionLocalProvider(
                        LocalSearchSources provides searchSources,
                        LocalSearchImageUrls provides searchPresentation.imageUrls,
                    ) {
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
}
