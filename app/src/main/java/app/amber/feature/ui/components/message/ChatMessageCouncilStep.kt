package app.amber.feature.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WandSparkles
import app.amber.feature.modelcouncil.ModelCouncilManager
import app.amber.feature.modelcouncil.ModelCouncilRolePresets
import app.amber.feature.subagent.SubAgentManager
import app.amber.feature.subagent.readSubAgentDisplayTextFromTranscript
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.JsonInstant
import org.koin.compose.koinInject
import java.io.File

/**
 * Render a coalesced Model Council run: one card replaces the multiple model_council_* tool calls
 * sharing one run_id. Tap → opens [ModelCouncilRunSheet] showing per-seat live streaming output
 * in a tab layout.
 */
@Composable
fun CouncilTaskStepView(
    step: ThinkingStep.CouncilTaskStep,
    loading: Boolean,
) {
    val manager: ModelCouncilManager = koinInject()
    val context = LocalContext.current
    val snapshot = manager.snapshot(step.runId)
    var persistedPayloads by remember(step.runId) { mutableStateOf(emptyList<JsonObject>()) }
    LaunchedEffect(step.runId, step.tools, snapshot == null) {
        persistedPayloads = if (snapshot == null) {
            withContext(Dispatchers.IO) {
                readCouncilTranscriptPayloads(
                    tools = step.tools,
                    runRoot = File(context.filesDir, "amberagent/model-council/runs"),
                )
            }
        } else {
            emptyList()
        }
    }
    var showSheet by remember(step.runId) { mutableStateOf(false) }
    val fallbackPayloads = persistedPayloads.takeIf { snapshot == null }.orEmpty()
    val parsedStatus = parseLatestCouncilStatus(step.tools, fallbackPayloads)
    val isRunning = parsedStatus == ModelCouncilCardStatus.RUNNING
    val phaseLabels = listOf(
        stringResource(R.string.chat_message_council_phase_hearing),
        stringResource(R.string.chat_message_council_phase_debate),
        stringResource(R.string.chat_message_council_phase_deliberation),
        stringResource(R.string.chat_message_council_phase_verdict),
    )
    var phaseIndex by remember(step.runId) { mutableIntStateOf(0) }
    LaunchedEffect(step.runId, isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (true) {
            delay(PHASE_LABEL_INTERVAL_MS)
            phaseIndex = (phaseIndex + 1) % phaseLabels.size
        }
    }
    val currentPhase = if (isRunning) phaseLabels[phaseIndex.coerceIn(phaseLabels.indices)]
        else phaseLabels.last()
    val statusVerb = parsedStatus.displayLabel()
    val title = if (isRunning) {
        stringResource(R.string.chat_message_council_title_running, statusVerb, currentPhase)
    } else {
        stringResource(R.string.chat_message_council_title_status, statusVerb)
    }
    val seatTemplate = stringResource(R.string.chat_message_council_seats)
    val seatSubtitle = remember(step.runId, step.tools, snapshot?.seats, fallbackPayloads, seatTemplate) {
        val names = snapshot?.seats
            ?.map { it.name.ifBlank { ModelCouncilRolePresets.byName(it.role)?.name ?: it.role } }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: extractCouncilSeatEntries(step.tools, fallbackPayloads).map { it.label }
        names.takeIf { it.isNotEmpty() }?.joinToString(" / ")?.let { seatNames ->
            seatTemplate.format(seatNames)
        }
    }
    AgentToolCallCapsule(
        title = title,
        toolName = "model_council",
        icon = Lucide.WandSparkles,
        kind = AgentToolKind.GENERIC,
        status = parsedStatus.toAgentToolStatus(),
        loading = loading && isRunning,
        onClick = { showSheet = true },
        approvalActions = null,
        subtitle = seatSubtitle,
    )

    if (showSheet) {
        ModelCouncilRunSheet(
            step = step,
            persistedPayloads = fallbackPayloads,
            onDismiss = { showSheet = false },
        )
    }
}

internal enum class ModelCouncilCardStatus {
    RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED
}

@Composable
private fun ModelCouncilCardStatus.displayLabel(): String = when (this) {
    ModelCouncilCardStatus.RUNNING -> stringResource(R.string.chat_message_council_status_running)
    ModelCouncilCardStatus.COMPLETED -> stringResource(R.string.chat_message_council_status_completed)
    ModelCouncilCardStatus.PARTIAL_FAILED -> stringResource(R.string.chat_message_council_status_partial_failed)
    ModelCouncilCardStatus.FAILED -> stringResource(R.string.chat_message_council_status_failed)
    ModelCouncilCardStatus.CANCELLED -> stringResource(R.string.chat_message_council_status_cancelled)
    ModelCouncilCardStatus.TIMED_OUT -> stringResource(R.string.chat_message_council_status_timed_out)
    ModelCouncilCardStatus.INTERRUPTED -> stringResource(R.string.chat_message_council_status_interrupted)
}

/** Walk model_council_* tools in reverse, find the most recent parsable `status`. */
internal fun parseLatestCouncilStatus(
    tools: List<UIMessagePart.Tool>,
    persistedPayloads: List<JsonObject> = emptyList(),
): ModelCouncilCardStatus {
    val toolStatus = latestCouncilStatus(tools.mapNotNull { it.cachedOutputJsonObject() })
    // A terminal tool result (readMissingRun/finished read) is the current conversation's
    // authoritative state. A start-only running result can be stale after a process restart,
    // so let the durable transcript provide its terminal state in that case.
    if (toolStatus != null && toolStatus != ModelCouncilCardStatus.RUNNING) return toolStatus
    return latestCouncilStatus(persistedPayloads) ?: toolStatus ?: ModelCouncilCardStatus.RUNNING
}

private fun latestCouncilStatus(payloads: List<JsonObject>): ModelCouncilCardStatus? =
    payloads.asReversed().firstNotNullOfOrNull { parsed ->
        val statusStr = (parsed["status"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
        ModelCouncilCardStatus.entries.firstOrNull { it.name.equals(statusStr, ignoreCase = true) }
    }

internal fun ModelCouncilCardStatus.toAgentToolStatus(): AgentToolStatus = when (this) {
    ModelCouncilCardStatus.RUNNING -> AgentToolStatus.RUNNING
    ModelCouncilCardStatus.COMPLETED -> AgentToolStatus.SUCCEEDED
    ModelCouncilCardStatus.PARTIAL_FAILED -> AgentToolStatus.FAILED
    ModelCouncilCardStatus.FAILED,
    ModelCouncilCardStatus.TIMED_OUT,
    ModelCouncilCardStatus.INTERRUPTED -> AgentToolStatus.FAILED
    ModelCouncilCardStatus.CANCELLED -> AgentToolStatus.CANCELLED
}

/**
 * Bottom sheet showing the council run's task + per-seat live streaming output, organized in
 * tabs. Synthesizer pane is the default tab; each seat (in run order) gets its own tab. Falls
 * back to extracting final text from tool result when the live flow is gone (process restart).
 */
@Composable
private fun ModelCouncilRunSheet(
    step: ThinkingStep.CouncilTaskStep,
    persistedPayloads: List<JsonObject>,
    onDismiss: () -> Unit,
) {
    val manager: ModelCouncilManager = koinInject()
    val workspace = workspaceColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val anchor = step.anchor
    val arguments = remember(anchor.input) { MessageRenderCache.toolInputJson(anchor.input) }
    val taskObjective = remember(arguments) {
        runCatching {
            (arguments.jsonObject["task"] as? JsonObject)?.get("objective")?.let { it as? JsonPrimitive }?.contentOrNull
                ?: arguments.jsonObject["objective"]?.let { it as? JsonPrimitive }?.contentOrNull
        }.getOrNull()
    }

    val snapshot = remember(step.runId) { manager.snapshot(step.runId) }
    val fallbackPayloads = persistedPayloads.takeIf { snapshot == null }.orEmpty()
    val parsedStatus = parseLatestCouncilStatus(step.tools, fallbackPayloads)
    val isRunning = parsedStatus == ModelCouncilCardStatus.RUNNING
    val statusVerb = parsedStatus.displayLabel()

    // Resolve seat list from the snapshot (preserves run order). Synthesizer is appended last.
    val synthesisLabel = stringResource(R.string.chat_message_council_synthesis)
    val seatTabs = remember(step.runId, snapshot?.seats, step.tools, fallbackPayloads, synthesisLabel) {
        val seatEntries = snapshot?.seats?.map { seat ->
            CouncilTabEntry(
                key = seat.seatId,
                label = seat.name.ifBlank { ModelCouncilRolePresets.byName(seat.role)?.name ?: seat.role },
            )
        }?.takeIf { it.isNotEmpty() } ?: extractCouncilSeatEntries(step.tools, fallbackPayloads)
        // Synthesizer pane is conceptually the "verdict"; show it first so the user sees the
        // bottom-line answer immediately when the run finishes.
        listOf(CouncilTabEntry(ModelCouncilManager.SYNTHESIZER_SEAT_KEY, synthesisLabel)) + seatEntries
    }

    // While the run is still going, default to the FIRST seat tab (index 1) instead of the
    // synthesizer (index 0) — the synthesizer has nothing to show until debate is over.
    // After completion, jump to synthesizer (index 0) for the verdict.
    val initialTab = if (isRunning && seatTabs.size > 1) 1 else 0
    var selectedTab by remember(step.runId, isRunning) { mutableIntStateOf(initialTab) }
    val safeSelected = selectedTab.coerceIn(0, (seatTabs.size - 1).coerceAtLeast(0))

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Lucide.WandSparkles,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = workspace.muted,
                )
                Text(
                    text = "@Council",
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
                CouncilObjectiveCard(objective = objective)
            }

            if (seatTabs.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = safeSelected,
                    edgePadding = 0.dp,
                    containerColor = workspace.paper,
                    contentColor = workspace.ink,
                ) {
                    seatTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == safeSelected,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider(color = workspace.hairline)

            // Active tab body — subscribe to the seat's live flow + auto-scroll while running.
            val activeSeatKey = seatTabs.getOrNull(safeSelected)?.key
            val seatFlow = remember(step.runId, activeSeatKey) {
                activeSeatKey?.let { manager.liveTextFlow(step.runId, it) } ?: MutableStateFlow("")
            }
            val liveText by seatFlow.collectAsState()
            val failureTemplate = stringResource(R.string.chat_message_council_failure)
            val warningTemplate = stringResource(R.string.chat_message_council_warning)
            val finalText = remember(
                step.tools,
                activeSeatKey,
                fallbackPayloads,
                failureTemplate,
                warningTemplate,
            ) {
                if (activeSeatKey == ModelCouncilManager.SYNTHESIZER_SEAT_KEY) {
                    extractFinalCouncilSynthesisText(step.tools, fallbackPayloads)
                } else if (activeSeatKey != null) {
                    extractFinalCouncilSeatText(
                        tools = step.tools,
                        seatId = activeSeatKey,
                        persistedPayloads = fallbackPayloads,
                        failureTemplate = failureTemplate,
                        warningTemplate = warningTemplate,
                    )
                } else ""
            }
            val displayTextSource = if (isRunning) {
                liveText.ifBlank { finalText }
            } else {
                finalText.ifBlank { liveText }
            }
            val displayText = remember(displayTextSource) { displayTextSource.cleanCouncilLineBreaks() }
            val activeModelLabel = remember(step.tools, activeSeatKey, fallbackPayloads) {
                activeSeatKey?.let { extractCouncilModelLabel(step.tools, it, fallbackPayloads) }.orEmpty()
            }
            val scrollState = rememberScrollState()
            var followBottom by remember(step.runId, activeSeatKey) { mutableStateOf(true) }
            LaunchedEffect(scrollState, isRunning, activeSeatKey) {
                snapshotFlow { scrollState.value to scrollState.maxValue }
                    .collect { (value, maxValue) ->
                        if (isRunning && value >= (maxValue - 8).coerceAtLeast(0)) {
                            followBottom = true
                        }
                    }
            }
            // Scroll spec aligned with ChatList.scrollToTimelineBottom — tween 80ms,
            // LinearEasing. Default spring's long settle holds isScrollInProgress and
            // would gate off subsequent chunks (same regression shape 1.8.10 fixed
            // on the main chat path).
            LaunchedEffect(displayText, isRunning, activeSeatKey, followBottom) {
                if (isRunning && followBottom) {
                    scrollState.animateScrollTo(
                        value = scrollState.maxValue,
                        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
                    )
                }
            }
            if (activeModelLabel.isNotBlank()) {
                Text(
                    text = activeModelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(isRunning, activeSeatKey) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (isRunning) followBottom = false
                        }
                    }
                    .verticalScroll(scrollState),
            ) {
                if (displayText.isBlank()) {
                    Text(
                        text = if (isRunning) {
                            stringResource(R.string.chat_message_council_waiting_output)
                        } else {
                            stringResource(R.string.chat_message_council_no_output)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspace.faint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    SelectionContainer(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CouncilRoundContent(
                            content = displayText,
                            streaming = isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

internal data class CouncilTabEntry(val key: String, val label: String)

private data class CouncilRoundSection(
    val label: String?,
    val content: String,
)

@Composable
private fun CouncilRoundContent(
    content: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val roundTemplate = stringResource(R.string.chat_message_council_round)
    val sections = remember(content, roundTemplate) {
        content.toCouncilRoundSections(roundTemplate)
    }
    val workspace = workspaceColors()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { section ->
            section.label?.let { CouncilRoundDivider(label = it) }
            if (section.content.isNotBlank()) {
                MarkdownBlock(
                    content = section.content,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium.copy(color = workspace.ink),
                    streaming = streaming,
                )
            }
        }
    }
}

@Composable
private fun CouncilRoundDivider(label: String) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // V3 review P3 #8: Council marker 切 chatTheme.accent 适配 Paper/Midnight
        val chatAccent = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent
        Surface(
            shape = RoundedCornerShape(50),
            color = chatAccent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, chatAccent.copy(alpha = 0.24f)),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = chatAccent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = chatAccent.copy(alpha = 0.20f),
        )
    }
}

@Composable
private fun CouncilObjectiveCard(objective: String) {
    val workspace = workspaceColors()
    val shouldCollapse = remember(objective) { objective.shouldCollapseCouncilObjective() }
    var expanded by remember(objective) { mutableStateOf(!shouldCollapse) }
    val objectiveScrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(enabled = shouldCollapse) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = workspace.row,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_message_council_topic),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                    modifier = Modifier.weight(1f),
                )
                if (shouldCollapse) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.chain_of_thought_collapse)
                        } else {
                            stringResource(R.string.chat_message_council_expand)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
                    )
                }
            }
            Text(
                text = objective,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.ink,
                maxLines = if (expanded) Int.MAX_VALUE else COUNCIL_OBJECTIVE_COLLAPSED_LINES,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (expanded && shouldCollapse) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(objectiveScrollState)
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        }
    }
}

private fun String.shouldCollapseCouncilObjective(): Boolean {
    val lineCount = lineSequence().count()
    return length > COUNCIL_OBJECTIVE_COLLAPSE_CHARS || lineCount > COUNCIL_OBJECTIVE_COLLAPSE_LINES
}

private fun String.toCouncilRoundSections(roundTemplate: String): List<CouncilRoundSection> {
    if (!contains(COUNCIL_ROUND_MARKER_TEXT)) {
        return listOf(CouncilRoundSection(label = null, content = this))
    }
    val sections = mutableListOf<CouncilRoundSection>()
    var label: String? = null
    val body = StringBuilder()
    var sawInternalMarker = false

    fun flush() {
        val content = body.toString().trim('\n')
        if (label != null || content.isNotBlank()) {
            sections += CouncilRoundSection(label = label, content = content)
        }
        body.clear()
    }

    lineSequence().forEach { line ->
        val match = COUNCIL_ROUND_MARKER.find(line)
        if (match != null) {
            sawInternalMarker = true
            val before = line.substring(0, match.range.first).trim()
            if (before.isNotBlank() && !before.contains(COUNCIL_ROUND_MARKER_TEXT)) {
                body.appendLine(before)
            }
            flush()
            val round = Regex("""\d+""").find(match.groupValues[1])?.value?.toIntOrNull()
            label = round?.let { roundTemplate.format(it) }
            val after = line.substring(match.range.last + 1).trim()
            if (after.isNotBlank() && !after.contains(COUNCIL_ROUND_MARKER_TEXT)) {
                body.appendLine(after)
            }
        } else if (line.contains(COUNCIL_ROUND_MARKER_TEXT)) {
            // The marker is an internal protocol token. Drop malformed or embedded variants
            // instead of letting the Chinese token leak into a localized UI.
            sawInternalMarker = true
        } else {
            body.appendLine(line)
        }
    }
    flush()

    return if (sawInternalMarker) {
        sections
    } else {
        listOf(CouncilRoundSection(label = null, content = this))
    }
}

private const val COUNCIL_OBJECTIVE_COLLAPSED_LINES = 3
private const val COUNCIL_OBJECTIVE_COLLAPSE_LINES = 5
private const val COUNCIL_OBJECTIVE_COLLAPSE_CHARS = 180
private const val COUNCIL_ROUND_MARKER_TEXT = "--- 第"
private val COUNCIL_ROUND_MARKER = Regex("""---\s*(第\s*\d+\s*轮)\s*---""")

/** Pull the synthesizer's final text from the latest read/wait result (`result.finalRecommendation`). */
internal fun extractFinalCouncilSynthesisText(
    tools: List<UIMessagePart.Tool>,
    persistedPayloads: List<JsonObject> = emptyList(),
): String {
    for (parsed in councilPayloads(tools, persistedPayloads)) {
        val result = parsed.payloadObject("result") ?: continue
        val recommendation = ((result["final_recommendation"] as? JsonPrimitive)?.contentOrNull)
            ?.takeIf { it.isNotBlank() }
            ?: ((result["error"] as? JsonPrimitive)?.contentOrNull)
        if (!recommendation.isNullOrBlank()) return recommendation
    }
    return ""
}

private fun councilPayloads(
    tools: List<UIMessagePart.Tool>,
    persistedPayloads: List<JsonObject>,
): List<JsonObject> {
    val toolPayloads = tools.mapNotNull { it.cachedOutputJsonObject() }
    // Tool calls are the current conversation's authoritative state. The transcript only fills
    // gaps after a process restart (for example, seats omitted by readMissingRun).
    return toolPayloads.asReversed() + persistedPayloads.asReversed()
}

/** Pull the provider/model label for a council seat or synthesizer from the latest payload. */
internal fun extractCouncilModelLabel(
    tools: List<UIMessagePart.Tool>,
    seatId: String,
    persistedPayloads: List<JsonObject> = emptyList(),
): String {
    for (parsed in councilPayloads(tools, persistedPayloads)) {
        val labels = parsed.payloadObject("seat_model_labels")
        val direct = (labels?.get(seatId) as? JsonPrimitive)?.contentOrNull
        if (!direct.isNullOrBlank()) return direct

        val parsedTurns = parsed.payloadArray("turns") ?: continue
        parsedTurns.forEach { turnElement ->
            val turn = turnElement as? JsonObject ?: return@forEach
            val tSeatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
            if (tSeatId != seatId) return@forEach
            val providerName = (turn["provider_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val modelName = (turn["model_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val label = listOf(providerName, modelName)
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            if (label.isNotBlank()) return label
        }
    }
    return ""
}

internal fun extractCouncilSeatEntries(
    tools: List<UIMessagePart.Tool>,
    persistedPayloads: List<JsonObject> = emptyList(),
): List<CouncilTabEntry> {
    for (parsed in councilPayloads(tools, persistedPayloads)) {
        val seats = parsed.payloadArray("seats")?.mapNotNull { (it as? JsonObject)?.toCouncilTabEntry() }
            ?.distinctBy { it.key }
            .orEmpty()
        if (seats.isNotEmpty()) return seats

        val parsedTurns = parsed.payloadArray("turns") ?: continue
        val entries = linkedMapOf<String, String>()
        parsedTurns.forEach { turnElement ->
            val turn = turnElement as? JsonObject ?: return@forEach
            val seatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val name = (turn["seat_name"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            entries.putIfAbsent(seatId, name)
        }
        if (entries.isNotEmpty()) {
            return entries.map { (seatId, name) -> CouncilTabEntry(key = seatId, label = name) }
        }
    }
    return emptyList()
}

private fun JsonObject.toCouncilTabEntry(): CouncilTabEntry? {
    val key = (this["seat_id"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val label = (this["name"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?: (this["seat_name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        ?: (this["role"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { ModelCouncilRolePresets.byName(it)?.name ?: it }
        ?: return null
    return CouncilTabEntry(key = key, label = label)
}

/**
 * Pull a specific seat's content across ALL rounds from the latest tool result, joined the same
 * way the live flow renders them (with `--- 第 N 轮 ---` separators between rounds 2+). Walking
 * tools in reverse means we pick the most recent (richest) turns array; within it, all matching
 * turns are kept in original order.
 */
internal fun extractFinalCouncilSeatText(
    tools: List<UIMessagePart.Tool>,
    seatId: String,
    persistedPayloads: List<JsonObject> = emptyList(),
    failureTemplate: String = "失败：%s",
    warningTemplate: String = "提示：%s",
): String {
    for (parsed in councilPayloads(tools, persistedPayloads)) {
        val parsedTurns = parsed.payloadArray("turns") ?: continue
        val matching = parsedTurns.mapNotNull { turnElement ->
            val turn = turnElement as? JsonObject ?: return@mapNotNull null
            val tSeatId = (turn["seat_id"] as? JsonPrimitive)?.contentOrNull
            if (tSeatId != seatId) return@mapNotNull null
            val round = (turn["round"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
            val warnings = (turn["warnings"] as? JsonArray)?.mapNotNull { warning ->
                (warning as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val content = (turn["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (turn["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { error -> failureTemplate.format(error) }
                ?: return@mapNotNull null
            val text = buildString {
                warnings.forEach { warning -> appendLine(warningTemplate.format(warning)) }
                if (warnings.isNotEmpty()) appendLine()
                append(content)
            }
            round to text
        }.sortedBy { it.first }
        if (matching.isNotEmpty()) {
            return matching.joinToString("\n\n") { (round, content) ->
                "--- 第 $round 轮 ---\n\n$content"
            }
        }
    }
    return ""
}

internal fun JsonObject.payloadObject(name: String): JsonObject? {
    val value = this[name] ?: return null
    return (value as? JsonObject)
        ?: (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
            runCatching { JsonInstant.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
}

private fun JsonObject.payloadArray(name: String): JsonArray? {
    val value = this[name] ?: return null
    return (value as? JsonArray)
        ?: (value as? JsonPrimitive)?.contentOrNull?.let { raw ->
            runCatching { JsonInstant.parseToJsonElement(raw).jsonArray }.getOrNull()
        }
}

/**
 * Read the durable council events when the in-memory manager was lost on process restart. The
 * transcript is only trusted inside the app-owned model-council run directory.
 */
internal fun readCouncilTranscriptPayloads(
    tools: List<UIMessagePart.Tool>,
    runRoot: File,
): List<JsonObject> {
    val transcriptPath = tools.asReversed().firstNotNullOfOrNull { tool ->
        runCatching {
            val parsed = tool.cachedOutputJsonObject() ?: return@runCatching null
            (parsed["transcript_path"] as? JsonPrimitive)?.contentOrNull
                ?: (parsed["run_id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runId -> File(runRoot, "$runId.jsonl").absolutePath }
        }.getOrNull()
    } ?: return emptyList()

    val root = runCatching { runRoot.canonicalFile }.getOrNull() ?: return emptyList()
    val transcript = runCatching { File(transcriptPath).canonicalFile }.getOrNull() ?: return emptyList()
    if (!transcript.isFile || transcript.extension != "jsonl") return emptyList()
    if (transcript.parentFile?.canonicalFile != root) return emptyList()

    val payloads = runCatching {
        transcript.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    (JsonInstant.parseToJsonElement(line) as? JsonObject)
                        ?.payloadObject("payload")
                }.getOrNull()
            }.toList()
        }
    }.getOrDefault(emptyList())

    val turnEvents = payloads.mapNotNull { it.payloadObject("turn") }
    return if (turnEvents.isEmpty() || payloads.any { it.payloadArray("turns") != null }) {
        payloads
    } else {
        payloads + JsonObject(mapOf("turns" to JsonArray(turnEvents)))
    }
}

/**
 * Pull the canonical final-text field from the latest tool result.
 *
 * Note: [app.amber.feature.subagent.SubAgentManager.runToPayload] encodes the
 * `SubAgentResult` as a **JSON-encoded string field** (calls `json.encodeToString(result)` and
 * stores the resulting string), not a nested object. So `payload["result"]` is a
 * `JsonPrimitive` carrying serialized JSON text — must be re-parsed before reading `summary`.
 * Every cast uses `as?` to avoid `Element X is not a JsonObject` crashes on partial / shaped-
 * differently outputs.
 */
internal fun extractFinalSubAgentText(tools: List<UIMessagePart.Tool>): String {
    for (tool in tools.asReversed()) {
        val parsed = tool.cachedOutputJsonObject() ?: continue
        val resultStr = (parsed["result"] as? JsonPrimitive)?.contentOrNull
        val nestedSummary = resultStr?.let { rs ->
            runCatching {
                ((JsonInstant.parseToJsonElement(rs) as? JsonObject)?.get("summary")
                    as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
        }
        val summary = nestedSummary
            ?: (parsed["summary"] as? JsonPrimitive)?.contentOrNull
        if (!summary.isNullOrBlank()) return summary
    }
    return ""
}

internal suspend fun extractSubAgentDisplayTextFromTranscript(
    tools: List<UIMessagePart.Tool>,
    runRoot: File,
): String =
    withContext(Dispatchers.IO) {
        val transcriptPath = tools.asReversed().firstNotNullOfOrNull { tool ->
            runCatching {
                val parsed = tool.cachedOutputJsonObject()
                    ?: return@runCatching null
                val explicitPath = (parsed["transcript_path"] as? JsonPrimitive)?.contentOrNull
                explicitPath ?: (parsed["run_id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runId -> File(runRoot, "$runId.jsonl").absolutePath }
            }.getOrNull()
        } ?: return@withContext ""

        readSubAgentDisplayTextFromTranscript(transcriptPath, runRoot)
    }

private fun UIMessagePart.Tool.cachedOutputJsonObject(): JsonObject? =
    MessageRenderCache.toolOutputJson(output) as? JsonObject

/**
 * Fix legacy council text where [UIMessage.toText] joined streaming deltas with "\n" separators,
 * producing single-character lines like "根据\n常见\n控\n糖\n...". Collapses spurious line breaks
 * while preserving intentional paragraph breaks (double newlines) and Markdown structure.
 */
private fun String.cleanCouncilLineBreaks(): String {
    if (!contains('\n')) return this
    val lines = lines()
    val shortLineRatio = lines.count { it.trim().length in 1..3 }.toFloat() / lines.size.coerceAtLeast(1)
    if (shortLineRatio < 0.35f) return this
    return replace(Regex("""(?<!\n)\n(?!\n)""")) { match ->
        val before = getOrNull(match.range.first - 1)
        val after = getOrNull(match.range.last + 1)
        if (before in listOf(':', '-', '。', '！', '？', '.', '!', '?') || after == '#' || after == '-' || after == '*') "\n"
        else ""
    }
}
