package app.amber.core.service

import android.content.Context
import android.util.Log
import app.amber.agent.R
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.model.Conversation
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.argsDigest
import app.amber.feature.tools.capabilityForTool
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "ChatApprovalActions"
private const val ASK_USER_TOOL_NAME = "ask_user"

private val TOOL_APPROVAL_CONTINUATION_WORDS = setOf(
    "继续",
    "继续吧",
    "可以继续",
    "执行",
    "执行吧",
    "确认",
    "同意",
    "批准",
    "ok",
    "yes",
    "y",
    "continue",
    "goahead",
    "approve",
    "approved",
)

internal data class ApprovalDecisionResult(val shouldResume: Boolean, val applied: Boolean = true)

internal data class IdleToolBlockerDecision(
    val changed: Boolean,
    val shouldResume: Boolean,
    val blockingToolNames: List<String>,
) {
    companion object {
        val Unchanged = IdleToolBlockerDecision(false, false, emptyList())
    }
}

/** Persists user decisions; the run coordinator retains job and continuation ownership. */
class ChatApprovalActions(
    private val context: Context,
    private val json: Json,
    private val conversationStore: ConversationStore,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val runTerminalStore: RunTerminalStore? = null,
    private val runRecovery: RunRecoveryService? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val capabilityPermissionStore: CapabilityPermissionStore? = null,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    private val trustedRunToolNames = ConcurrentHashMap<Uuid, Set<String>>()
    private val _outcomeUnknown = MutableStateFlow<Map<String, List<OutcomeUnknownPrompt>>>(emptyMap())
    val outcomeUnknownFlow: StateFlow<Map<String, List<OutcomeUnknownPrompt>>> = _outcomeUnknown.asStateFlow()

    fun trustedToolNames(conversationId: Uuid): Set<String> = trustedRunToolNames[conversationId].orEmpty()

    fun clearTrust(conversationId: Uuid) {
        trustedRunToolNames.remove(conversationId)
    }

    fun clearAllTrust() {
        trustedRunToolNames.clear()
    }

    private suspend fun useDurableRuntime(): Boolean = chatDurableRuntimeEnabled(
        capabilityFlags, toolEffectLedger, runTerminalStore, runRecovery,
    )

    internal suspend fun applyToolApprovalDecision(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): ApprovalDecisionResult {
        val conversation = conversationStore.ensureFullConversationLoaded(conversationId)
        // The predecessor can finish another decision while this request waits for its handoff.
        // Only a still-pending tool on the selected history can consume this user action.
        if (conversation.currentMessages.none { message ->
                message.getTools().any { it.toolCallId == toolCallId && it.isPending }
            }
        ) return ApprovalDecisionResult(shouldResume = false, applied = false)
        val approvedToolName = conversation.findToolName(toolCallId)
        val newApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        if (approved && approvedToolName in screenSessionTrustTools()) {
            trustedRunToolNames[conversationId] = screenSessionTrustTools()
        }

        // Update the tool approval state
        val approvedInputs = mutableListOf<Pair<String, String>>() // toolCallId to approved input
        val updatedNodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map { message ->
                val parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool || part.toolCallId != toolCallId) {
                        part
                    } else {
                        // A recipe's nested step uses the same top-level pending tool as its
                        // durable approval checkpoint. Grant only that pending nested step.
                        val resumeInput = if (approved && answer == null) {
                            recipeResumeInputForApproval(part)
                        } else {
                            part.input
                        }
                        approvedInputs += part.toolCallId to resumeInput
                        part.copy(input = resumeInput, approvalState = newApprovalState)
                    }
                }
                message.copy(parts = parts)
            }
            node.copy(messages = messages)
        }
        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        conversationStore.saveConversation(conversationId, updatedConversation)
        approvedInputs.forEach { (callId, input) ->
            recordCapabilityApproval(callId, approvedToolName, input, approved, source = "user")
        }

        // Check if there are still pending tools
        val hasPendingTools = updatedNodes.any { node -> node.currentMessage.getTools().any { it.isPending } }
        return ApprovalDecisionResult(shouldResume = !hasPendingTools)
    }

    suspend fun pendingRunForCurrentApprovalSettings(
        conversationId: Uuid,
        autoApproveHighRiskTools: Boolean,
    ): AgentRunId? {
        if (!autoApproveHighRiskTools) return null
        if (!useDurableRuntime()) return null
        val waitingRun = runTerminalStore?.activeForConversation(conversationId.toString())
            ?.takeIf { it.state == RunTerminalState.WAITING_USER }
            ?: return null
        val expectedRunId = app.amber.core.agent.runtime.AgentRunId(waitingRun.runId)
        if (!isExpectedWaitingUserRun(waitingRun, conversationId, expectedRunId)) return null
        val conversation = conversationStore.ensureFullConversationLoaded(conversationId)
        val pendingTools = conversation.currentMessages.lastOrNull()?.getTools()
            ?.filter { it.isPending }
            .orEmpty()
        // ask_user remains an explicit human interaction. Do not re-arm a
        // mixed approval batch from a global setting; the kernel independently
        // rechecks the policy before any execution.
        if (
            pendingTools.isEmpty() ||
            pendingTools.any { tool ->
                tool.toolName == ASK_USER_TOOL_NAME ||
                    // Jev 复核收紧的调用只能由用户逐个决定。
                    tool.metadata?.containsKey(app.amber.core.jev.JevAutoApprovalGate.METADATA_KEY) == true ||
                    tool.metadata?.containsKey(app.amber.core.jev.JevApprovalTriage.METADATA_KEY) == true ||
                    tool.metadata?.get("run_id")?.jsonPrimitive?.contentOrNull != waitingRun.runId
            }
        ) return null
        return expectedRunId
    }

    internal suspend fun approvePendingAutoApprovableTools(conversationId: Uuid): ApprovalDecisionResult? {
        val conversation = conversationStore.ensureFullConversationLoaded(conversationId)
        var changed = false
        val approvedAll = mutableListOf<Triple<String, String, String>>() // toolCallId, toolName, input

        val updatedNodes = conversation.messageNodes.map { node ->
            val messages = node.messages.map { message ->
                val parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Tool || !part.isPending || part.toolName == "ask_user" ||
                        // Jev 复核收紧的调用不随开关批量批准，只能由用户在卡片上决定。
                        part.metadata?.containsKey(app.amber.core.jev.JevAutoApprovalGate.METADATA_KEY) == true ||
                        part.metadata?.containsKey(app.amber.core.jev.JevApprovalTriage.METADATA_KEY) == true
                    ) {
                        part
                    } else {
                        changed = true
                        val resumeInput = recipeResumeInputForApproval(part)
                        approvedAll += Triple(part.toolCallId, part.toolName, resumeInput)
                        part.copy(input = resumeInput, approvalState = ToolApprovalState.Approved)
                    }
                }
                message.copy(parts = parts)
            }
            node.copy(messages = messages)
        }

        if (!changed) return null

        conversationStore.saveConversation(
            conversationId = conversationId,
            conversation = conversation.copy(messageNodes = updatedNodes)
        )
        approvedAll.forEach { (callId, toolName, input) ->
            recordCapabilityApproval(callId, toolName, input, approved = true, source = "approve_all")
        }

        val hasPendingTools = updatedNodes.any { node -> node.currentMessage.getTools().any { it.isPending } }

        return ApprovalDecisionResult(shouldResume = !hasPendingTools)
    }

    internal suspend fun recordCapabilityApproval(
        toolCallId: String,
        toolName: String?,
        input: String,
        approved: Boolean,
        source: String,
    ) {
        if (toolName == null) return
        val store = capabilityPermissionStore ?: return
        if (capabilityFlags?.isEnabled(Capability.CapabilityPermissions) != true) return
        val effect = runCatching { toolEffectLedger?.getByToolCallId(toolCallId) }.getOrNull()
        val digest = effect?.argsDigest ?: runCatching { argsDigest(input) }.getOrNull() ?: return
        val capability = capabilityForTool(toolName)
        val entry = if (approved) {
            ApprovalHistoryEntry.approved(
                capability = capability,
                toolName = toolName,
                runId = effect?.runId,
                toolCallId = toolCallId,
                effectId = effect?.effectId,
                argsDigest = digest,
                source = source,
            )
        } else {
            ApprovalHistoryEntry.denied(
                capability = capability,
                toolName = toolName,
                runId = effect?.runId,
                toolCallId = toolCallId,
                effectId = effect?.effectId,
                argsDigest = digest,
                source = source,
            )
        }
        runCatching { store.recordApproval(entry) }
    }

    suspend fun refreshOutcomeUnknown() {
        if (!useDurableRuntime()) {
            _outcomeUnknown.value = emptyMap()
            return
        }
        val prompts = mutableListOf<OutcomeUnknownPrompt>()
        runCatching {
            for (effect in toolEffectLedger!!.listOutcomeUnknown()) {
                val run = effect.runId?.let { runTerminalStore!!.get(it) } ?: continue
                prompts += OutcomeUnknownPrompt(
                    effectId = effect.effectId,
                    runId = run.runId,
                    conversationId = run.conversationId,
                    toolCallId = effect.toolCallId,
                    toolName = effect.toolName,
                    resultSummary = effect.resultSummary,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "refreshOutcomeUnknown failed", error)
        }
        _outcomeUnknown.value = prompts.groupBy { it.conversationId }
    }

    suspend fun reconcileOutcomeUnknown(conversationId: Uuid, effectId: String, retry: Boolean): Boolean =
        withCapturedRestoreWriteContext restore@{
            if (!useDurableRuntime()) return@restore false
            val effect = toolEffectLedger!!.get(effectId) ?: return@restore false
            val run = effect.runId?.let { runTerminalStore!!.get(it) } ?: return@restore false
            if (run.conversationId != conversationId.toString()) return@restore false

            val abandonedOutput = listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("status", "abandoned")
                            put(
                                "error",
                                "Tool execution outcome was unknown after an interruption; the user chose to abandon this call."
                            )
                            put("effect_id", effectId)
                        }
                    )
                )
            )
            toolEffectLedger!!.reconcile(
                effectId = effectId,
                retry = retry,
                abandonOutput = if (retry) emptyList() else abandonedOutput,
            )

            // Reset the tool part in the conversation: retry → resumable
            // (Approved, no output); abandon → structured rejection the model
            // can read in the next round.
            val conversation = conversationStore.ensureFullConversationLoaded(conversationId)
            var changed = false
            val updatedNodes = conversation.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                if (part !is UIMessagePart.Tool || part.toolCallId != effect.toolCallId) {
                                    return@map part
                                }
                                changed = true
                                if (retry) {
                                    part.copy(
                                        approvalState = ToolApprovalState.Approved,
                                        output = emptyList(),
                                    )
                                } else {
                                    part.copy(
                                        output = abandonedOutput,
                                        approvalState = ToolApprovalState.Denied(
                                            "Tool execution outcome was unknown after an interruption; " +
                                                "the user chose to abandon this call."
                                        ),
                                    )
                                }
                            }
                        )
                    }
                )
            }
            if (changed) {
                conversationStore.saveConversation(conversationId, conversation.copy(messageNodes = updatedNodes))
            }
            // P2-01: retry is an explicit user decision on this exact effect —
            // record it so the dispatcher's approval-digest check lets the same
            // args execute again (same runId + toolCallId + digest).
            if (retry) {
                recordCapabilityApproval(
                    toolCallId = effect.toolCallId,
                    toolName = effect.toolName,
                    input = "",
                    approved = true,
                    source = "outcome_retry",
                )
            }
            refreshOutcomeUnknown()
            // Resume the same conversation: retry re-executes the tool; abandon
            // lets the model see the structured rejection and continue.
            true
        }


    internal suspend fun resolveIdleToolBlockerBeforeDispatch(
        conversationId: Uuid,
        message: PendingUserMessage,
    ): IdleToolBlockerDecision {
        val currentConversation = conversationStore.getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return IdleToolBlockerDecision.Unchanged
        val lastMessage = lastNode.currentMessage
        val blockingTools = lastMessage.getTools().filter { !it.isExecuted }
        if (blockingTools.isEmpty()) return IdleToolBlockerDecision.Unchanged

        val userAnswer = message.previewText(
            maxChars = 4_000,
            copy = PendingUserMessageDisplayCopy.from(context),
        )
        val explicitApproval = message.isToolApprovalContinuation()
        val hasAskUserAnswer = userAnswer.isNotBlank() &&
            blockingTools.any { it.isPending && it.toolName == ASK_USER_TOOL_NAME }
        val shouldResume = explicitApproval || hasAskUserAnswer

        var changed = false
        val approvedContinuations = mutableListOf<Triple<String, String, String>>() // toolCallId, toolName, input
        val updatedMessage = lastMessage.copy(
            parts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) {
                    when {
                        part.toolName == ASK_USER_TOOL_NAME && hasAskUserAnswer -> {
                            changed = true
                            part.copy(approvalState = ToolApprovalState.Answered(userAnswer))
                        }

                        explicitApproval && part.isPending -> {
                            changed = true
                            val resumeInput = recipeResumeInputForApproval(part)
                            approvedContinuations += Triple(part.toolCallId, part.toolName, resumeInput)
                            part.copy(input = resumeInput, approvalState = ToolApprovalState.Approved)
                        }

                        explicitApproval -> {
                            changed = true
                            skipStaleToolForContinuation(part)
                        }

                        hasAskUserAnswer -> {
                            part
                        }

                        else -> {
                            changed = true
                            cancelToolForNewUserMessage(part)
                        }
                    }
                } else {
                    part
                }
            }
        )
        if (!changed || updatedMessage == lastMessage) return IdleToolBlockerDecision.Unchanged

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { nodeMessage ->
                    if (nodeMessage.id == lastMessage.id) updatedMessage else nodeMessage
                }
            ),
            updateAt = Instant.now(),
        )
        conversationStore.saveConversation(conversationId, updatedConversation)
        approvedContinuations.forEach { (callId, toolName, input) ->
            recordCapabilityApproval(callId, toolName, input, approved = true, source = "continuation")
        }
        return IdleToolBlockerDecision(
            changed = true,
            shouldResume = shouldResume,
            blockingToolNames = blockingTools.map { it.toolName },
        )
    }

    private fun PendingUserMessage.isToolApprovalContinuation(): Boolean {
        if (parts.any { it !is UIMessagePart.Text }) return false
        val raw = previewText(
            maxChars = 80,
            copy = PendingUserMessageDisplayCopy.from(context),
        ).trim().lowercase(Locale.ROOT)
        if (raw.isBlank()) return false
        val compact = raw.replace(Regex("""[\s\p{Punct}，。！？、；：「」『』（）【】《》]+"""), "")
        return compact in TOOL_APPROVAL_CONTINUATION_WORDS ||
            compact.startsWith("继续") ||
            compact.startsWith("可以继续")
    }

    private fun cancelToolForNewUserMessage(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"A new user message arrived before this pending tool was approved, so AmberAgent cancelled the stale tool state and continued the conversation."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Cancelled because a new user message arrived before approval")
        )
    }

    private fun skipStaleToolForContinuation(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            approvalState = ToolApprovalState.Denied("Skipped stale tool after user asked to continue")
        )
    }

    /** Bind a nested recipe resume to the one checkpoint the user approved. */
    private fun recipeResumeInputForApproval(tool: UIMessagePart.Tool): String = runCatching {
        if (!tool.toolName.startsWith("recipe_")) return@runCatching tool.input
        val input = json.parseToJsonElement(tool.input).jsonObject
        if (!input.containsKey(app.amber.feature.recipe.RECIPE_CHECKPOINT_INPUT_KEY)) {
            return@runCatching tool.input
        }
        buildJsonObject {
            input.forEach { (key, value) -> put(key, value) }
            put(app.amber.feature.recipe.RECIPE_RESUME_APPROVED_INPUT_KEY, true)
        }.toString()
    }.getOrDefault(tool.input)

    private fun screenSessionTrustTools(): Set<String> = setOf(
        "screen_read_ui",
        "screen_click",
        "screen_long_click",
        "screen_swipe",
        "screen_input_text",
        "screen_back",
        "screen_home",
        "screen_open_app",
        "screen_screenshot",
    )

    private suspend fun captureRestoreEpoch(): Long? {
        val gate = restoreWriteGate ?: return null
        return coroutineContext[SyncRestoreWriteEpoch]?.value ?: gate.currentEpoch()
    }

    private suspend fun <T> withRestoreEpoch(
        expectedRestoreEpoch: Long?,
        block: suspend () -> T,
    ): T {
        if (restoreWriteGate == null) return block()
        val epoch = expectedRestoreEpoch
            ?: coroutineContext[SyncRestoreWriteEpoch]?.value
            ?: restoreWriteGate.currentEpoch()
        return withContext(SyncRestoreWriteEpoch(epoch)) { block() }
    }

    /** Capture the caller's epoch before a read/transform/write operation. */
    private suspend fun <T> withCapturedRestoreWriteContext(
        block: suspend () -> T,
    ): T = withRestoreEpoch(captureRestoreEpoch(), block)

}

internal suspend fun chatDurableRuntimeEnabled(
    capabilityFlags: CapabilityFlags?,
    toolEffectLedger: ToolEffectLedger?,
    runTerminalStore: RunTerminalStore?,
    runRecovery: RunRecoveryService?,
): Boolean = capabilityFlags != null &&
    toolEffectLedger != null &&
    runTerminalStore != null &&
    runRecovery != null &&
    capabilityFlags.isEnabled(Capability.DurableToolEffects) &&
    capabilityFlags.isEnabled(Capability.TypedRunTerminal)

private fun Conversation.findToolName(toolCallId: String): String? =
    messageNodes.asSequence()
        .flatMap { it.messages.asSequence() }
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Tool>()
        .firstOrNull { it.toolCallId == toolCallId }
        ?.toolName
