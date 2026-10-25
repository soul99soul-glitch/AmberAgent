package app.amber.core.ai

import app.amber.ai.core.MessageRole
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.ToolApprovalState
import app.amber.core.ai.transformers.onGenerationFinish
import app.amber.core.ai.transformers.transforms
import app.amber.core.ai.transformers.visualTransforms
import app.amber.core.ai.transformers.visualTransformsStreamingTail
import app.amber.feature.runtime.AgentLoopBudgetPrompt
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.CapabilityPermissionContext
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.SpeculativeToolRunner
import app.amber.feature.runtime.ToolEffect
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolEffectProtocolMismatchException
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.ToolLedgerContext
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolExposureState
import app.amber.feature.tools.effectClass
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.agent.BuildConfig
import app.amber.agent.R
import java.util.Locale
import kotlin.time.Clock

private const val TAG = "RunKernel"

private const val PERF_TAG = "AmberChatPerf"

// Guard notes appended to the assistant turn when a kernel guard stops the
// loop. Both ride the plain-text channel; the run settles terminal
// (OUTPUT_LIMIT / GUARD_STOPPED via [GenerationTerminal]) — never COMPLETED —
// and the localized note persists with the messages.
/** Wire reason carried by [GenerationTerminal.GuardStopped]. */
private const val DUPLICATE_TOOL_CALL_GUARD_REASON = "duplicate_tool_call"

/** Fixed copy snapshot for kernel-owned text persisted in [UIMessagePart.Text]. */
private data class RunKernelMessages(
    val outputLimitNotice: String,
    val duplicateToolCallNotice: String,
    val duplicateSameBatchMessage: String,
    val duplicateExistingMessage: String,
    val protocolMismatchMessage: String,
    val completionCheckPrompt: String,
) {
    companion object {

        fun from(context: Context): RunKernelMessages = RunKernelMessages(
            outputLimitNotice = context.getString(R.string.run_kernel_output_limit_notice),
            duplicateToolCallNotice = context.getString(R.string.run_kernel_duplicate_tool_call_notice),
            duplicateSameBatchMessage = context.getString(R.string.run_kernel_duplicate_same_batch_message),
            duplicateExistingMessage = context.getString(R.string.run_kernel_duplicate_existing_message),
            protocolMismatchMessage = context.getString(R.string.run_kernel_protocol_mismatch_message),
            completionCheckPrompt = context.getString(R.string.run_kernel_completion_check_prompt),
        )
    }
}

/**
 * The runtime-owned tool loop: step budget, tool exposure, write-ahead
 * ledger, approval gating, dispatch, steer drain and terminal production —
 * the loop mechanics that used to be embedded in the consumer-facing
 * `Generator` facade (ChatRunCoordinator).
 *
 * One model round per step is delegated to the [GenerationRoundEngine] SPI;
 * everything around the round is loop policy owned here, so every consumer
 * (chat turn, subagent, DeepRead, Novel) shares identical loop semantics.
 */
class DefaultRunKernel(
    private val context: Context,
    private val toolDispatcher: AgentToolDispatcher,
    private val roundEngine: GenerationRoundEngine,
    private val toolEffectLedger: ToolEffectLedger? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val capabilityPermissionStore: CapabilityPermissionStore? = null,
    // Jev 自动批准复核：只把自动批准收紧为人工审批，从不放行。
    private val autoApprovalGate: app.amber.core.jev.JevAutoApprovalGate? = null,
    // Jev 审批分诊：独立安全交集，只收紧不放松。
    private val approvalTriage: app.amber.core.jev.JevApprovalTriage? = null,
    // Jev 完成声明校验：改过文件却未检查就宣称完成时，续跑一轮（每次 run 至多一次）。
    private val completionCheck: app.amber.core.jev.JevCompletionCheck? = null,
    // Jev 脚本判断：只评估既有脚本/工具输出。
    private val scriptJudgment: app.amber.core.jev.JevScriptJudgment? = null,
) : RunKernel {

    override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
        coroutineScope {
        val settings = session.settings
        val model = session.model
        val inputTransformers = session.inputTransformers
        val outputTransformers = session.outputTransformers
        val memories = session.memories
        val maxSteps = session.maxSteps
        val processingStatus = session.processingStatus
        val autoApproveTools = session.autoApproveTools
        val autoApproveHighRiskTools = session.autoApproveHighRiskTools
        val autoApprovedToolNames = session.autoApprovedToolNames
        val invocationContext = session.invocationContext
        val conversation = session.conversation
        val consumeSteerMessages = session.consumeSteerMessages
        val runId = session.runId
        val onTerminal = session.onTerminal
        val responsesResume = session.responsesResume
        val runKernelMessages = RunKernelMessages.from(context)
        // Step 6: the run's sandbox policy — read once, like every other
        // session field; the dispatcher enforces it on every execution.
        val executionPolicy = session.executionPolicy

        // Durable runtime path (P1-02 + P1-03): fixed for the whole run —
        // the flags are read once here, never mid-run.
        val durablePath = runId != null &&
            onTerminal != null &&
            toolEffectLedger != null &&
            capabilityFlags != null &&
            capabilityFlags.isEnabled(Capability.DurableToolEffects) &&
            capabilityFlags.isEnabled(Capability.TypedRunTerminal)

        // P2-01 capability permissions: fixed for the whole run. Non-null only
        // when the capability_permissions flag is on; null keeps the pre-P2-01
        // global normal/high-risk switch behavior untouched.
        val capabilityState = if (capabilityFlags?.isEnabled(Capability.CapabilityPermissions) == true &&
            capabilityPermissionStore != null
        ) {
            capabilityPermissionStore.state()
        } else {
            null
        }
        // These IDs come from the actual generation inputs. Workspace has no
        // identity in this call chain yet, so it intentionally remains unset;
        // no placeholder ID is allowed to make a scoped policy match.
        val permissionContext = CapabilityPermissionContext(
            assistantId = AMBER_AGENT_ID.toString(),
            conversationId = conversation?.id?.toString(),
            sessionId = runId,
        )

        var messages: List<UIMessage> = session.messages
        val approvalPlanner = ApprovalBatchPlanner(
            autoApproveHighRiskTools = autoApproveHighRiskTools,
            invocationContext = invocationContext,
            runId = runId,
            autoApprovalGate = autoApprovalGate,
            approvalTriage = approvalTriage,
            resolveDecision = { toolDef, tool ->
                toolDispatcher.resolveDecision(
                    toolDef = toolDef,
                    tool = tool,
                    autoApproveTools = autoApproveTools,
                    autoApproveHighRiskTools = autoApproveHighRiskTools,
                    autoApprovedToolNames = autoApprovedToolNames,
                    invocationContext = invocationContext,
                    capabilityPermissions = capabilityState,
                    permissionContext = permissionContext,
                )
            },
        )

        val toolExposure = ToolExposureState.from(session.tools)
        // A setting change can resume a run that was already parked at the
        // approval gate. Keep the original Pending set separate from tools
        // emitted by the current provider round: only this persisted assistant
        // turn (and its deferred Auto siblings after a decision) may be
        // re-evaluated here, and only once at the beginning of this kernel
        // invocation. A composite tool that pauses again later must return to
        // WaitingUser instead of being retried in a loop.
        val initialPendingToolCallIds = messages.lastOrNull()
            ?.getTools()
            ?.filter { it.isPending && !it.isExecuted }
            ?.mapTo(mutableSetOf()) { it.toolCallId }
            .orEmpty()
        var terminal: GenerationTerminal? = null
        var brokeEarly = false
        var completionNudged = false

        // Duplicate-tool-call guard memory (aligned with the iOS ToolLoopGuard):
        // signature (toolName + argsDigest) → number of counted executions
        // this run, plus the toolCallIds already counted. On the durable path
        // the same runId can re-enter run() many times (approval round, crash
        // resume), so the tables are seeded from the write-ahead ledger —
        // FINISHED, non-failure effects of THIS runId are executions that
        // already happened, by the same standard the in-run counting below
        // applies.
        val toolCallHistory = ToolCallHistory(
            duplicateSameBatchMessage = runKernelMessages.duplicateSameBatchMessage,
            duplicateExistingMessage = runKernelMessages.duplicateExistingMessage,
        )
        if (durablePath) {
            toolCallHistory.seed(toolEffectLedger!!.listByRun(runId!!))
        }

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Recipe import/rollback/delete may update the catalog while the
            // same generation is still looping. Refresh only at a model-round
            // boundary; the currently executing recipe keeps its definition
            // snapshot, while the next request sees the registry update.
            toolExposure.refreshDynamicTools()
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()
            // Composite tools (notably Recipe) can reach a nested approval
            // while their outer call is already executing. Persisted pending
            // state must stop the loop before another model request, just as
            // a model-produced approval does.
            val waitingTools = messages.lastOrNull()?.getTools()
                ?.filter { it.isPending }
                .orEmpty()
            // An Auto call can be deliberately deferred with a sibling that
            // needs approval: a batch stays intact until the user decides the
            // approval card. Only remember Auto calls when this same persisted
            // assistant turn already carries a pending or decided sibling.
            // A pure Auto turn may be a truncated provider emission, and must
            // never be promoted into execution by a later kernel invocation.
            val deferredAutoTools = if (pendingTools.isNotEmpty() || waitingTools.isNotEmpty()) {
                messages.lastOrNull()?.getTools()
                    ?.filter {
                        !it.isExecuted && it.approvalState == ToolApprovalState.Auto
                    }
                    .orEmpty()
            } else {
                emptyList()
            }
            // Keep definitions for already-persisted calls visible to this
            // step even when the lazy tool catalog has not exposed them yet.
            // They are needed only for the local approval re-check below; no
            // provider request is made until all waiting tools are settled.
            toolExposure.exposeToolNames((pendingTools + waitingTools + deferredAutoTools).map { it.toolName })
            val exposedTools = toolExposure.toolsForStep()
            val toolDefinitionsForPending = exposedTools.associateBy { it.name }
            val resumeApproval = approvalPlanner.planResume(
                stepIndex = stepIndex,
                initialPendingToolCallIds = initialPendingToolCallIds,
                pendingTools = pendingTools,
                waitingTools = waitingTools,
                deferredAutoTools = deferredAutoTools,
                toolDefinitions = toolDefinitionsForPending,
                recentUserTexts = messages.filter { it.role == MessageRole.USER }.takeLast(3).map { it.toText() },
            )
            if (resumeApproval.approvalUpdates.isNotEmpty()) {
                val decisionsByCallId = resumeApproval.approvalUpdates.associateBy { it.toolCallId }
                val assistantTurn = messages.last()
                val approvalSnapshot = assistantTurn.copy(
                    parts = assistantTurn.parts.map { part ->
                        if (part is UIMessagePart.Tool) decisionsByCallId[part.toolCallId] ?: part else part
                    },
                )
                messages = messages.dropLast(1) + approvalSnapshot
                emit(GenerationChunk.Messages(messages))
                terminal = GenerationTerminal.WaitingUser
                brokeEarly = true
                break
            }
            val autoToolsReadyForDispatch = resumeApproval.readyTools
            val hasResumableTools = pendingTools.isNotEmpty() || autoToolsReadyForDispatch.isNotEmpty()
            val loopBudgetPrompt = AgentLoopBudgetPrompt.build(stepIndex = stepIndex, maxSteps = maxSteps)
            val shouldHideToolsForBudget = AgentLoopBudgetPrompt.shouldHideTools(
                stepIndex = stepIndex,
                maxSteps = maxSteps,
                hasResumableTools = hasResumableTools,
            )
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools")
                addAll(
                    if (shouldHideToolsForBudget) {
                        emptyList()
                    } else {
                        exposedTools
                    }
                )
            }
            // Speculative execution is disabled on the durable path: a
            // speculative run bypasses the write-ahead ledger (no prepare —
            // the transaction protocol requires execution to happen after
            // prepare) and starts before the truncation guard can judge the
            // reply (the guard requires execution to happen after its
            // verdict, so half-emitted truncated calls stay unexecuted).
            val speculativeRunner = if (!durablePath &&
                settings.speculativeToolExecution.enabled &&
                settings.streamOutput
            ) {
                SpeculativeToolRunner(
                    scope = this,
                    dispatcher = toolDispatcher,
                    maxConcurrentTools = settings.speculativeToolExecution.maxConcurrentTools,
                    invocationContext = invocationContext,
                    capabilityPermissions = capabilityState,
                    permissionContext = permissionContext,
                    executionPolicy = executionPolicy,
                )
            } else {
                null
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Durable-path batch bookkeeping, populated only by the fresh-batch
            // branch below: the prepared write-ahead effects keyed by
            // toolCallId (approval metadata + guard-reject terminalization),
            // and the calls whose prepare() hit a protocol mismatch
            // (fail-closed — answered with a structured error, never executed).
            var preparedEffects: Map<String, ToolEffect> = emptyMap()
            var protocolFailures: List<UIMessagePart.Tool> = emptyList()

            // A newly enabled high-risk setting may release a Pending call
            // from the session snapshot. Deferred Auto siblings join the same
            // rechecked batch; any newly Pending tool above keeps the entire
            // batch at the human-input gate.
            if (autoToolsReadyForDispatch.isNotEmpty() &&
                (waitingTools.isEmpty() || resumeApproval.canReleaseWaitingToolsWithHighRisk)
            ) {
                Log.i(TAG, "rechecking ${autoToolsReadyForDispatch.size} persisted tool approvals")
                val dispatchableTools = (pendingTools + autoToolsReadyForDispatch)
                    .associateBy { it.toolCallId }
                toolsToProcess = messages.lastOrNull()?.getTools()
                    ?.mapNotNull { tool -> dispatchableTools[tool.toolCallId] }
                    .orEmpty()
            } else if (waitingTools.isNotEmpty()) {
                terminal = GenerationTerminal.WaitingUser
                brokeEarly = true
                break
            // Skip generation if we have approved/denied tool calls to handle
            } else if (pendingTools.isEmpty()) {
                var streamingVisualBaselineReady = false
                val roundOutcome = roundEngine.generateRound(
                    GenerationRoundRequest(
                        settings = settings,
                        messages = messages,
                        transformers = inputTransformers,
                        model = model,
                        tools = toolsInternal,
                        memories = memories ?: emptyList(),
                        stream = settings.streamOutput,
                        processingStatus = processingStatus,
                        conversation = conversation,
                        speculativeRunner = speculativeRunner,
                        loopBudgetPrompt = loopBudgetPrompt,
                        // A stored Responses response can resume only the first
                        // model round; later rounds must start fresh requests.
                        responsesResume = responsesResume?.let {
                            if (stepIndex == 0) it else it.copy(resumeFrom = null)
                        },
                        // Step 5: thread the durable stream so each model round
                        // leaves a RequestSnapshot at the provider boundary —
                        // gated on the same durablePath that gates the tool
                        // lifecycle below.
                        runId = runId,
                        stepIndex = stepIndex,
                        events = session.toolLifecycleEvents,
                        durablePath = durablePath,
                    ),
                    onUpdateMessages = { update ->
                        messages = update.messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            settings = settings.transformers
                        )
                        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        val visualMessages = if (update.isStreamingTail && streamingVisualBaselineReady) {
                            messages.visualTransformsStreamingTail(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                settings = settings.transformers
                            )
                        } else {
                            streamingVisualBaselineReady = true
                            messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                settings = settings.transformers
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                            Log.d(
                                PERF_TAG,
                                "streamFlush kind=${if (update.isStreamingTail) "tail" else "full"} " +
                                    "messages=${visualMessages.size} elapsedMs=${String.format(Locale.US, "%.2f", elapsedMs)}",
                            )
                        }
                        emit(
                            GenerationChunk.Messages(
                                messages = visualMessages,
                                update = update.withMessages(visualMessages),
                            )
                        )
                    },
                )
                val finalizedTurn = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    settings = settings.transformers
                ).onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    settings = settings.transformers
                )
                val completedAssistant = finalizedTurn.last().copy(
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )
                messages = finalizedTurn.dropLast(1) + completedAssistant
                emit(GenerationChunk.Messages(messages))

                // Truncation guard (aligned with the iOS reachedOutputLimit
                // semantics): a reply cut off by the output limit may carry
                // half-emitted tool calls whose JSON never completed —
                // executing them would act on truncated args. Stop before any
                // tool bookkeeping: no prepare, no approval gate, the calls
                // stay unexecuted. The guard note rides the plain-text channel
                // and the run settles as OUTPUT_LIMIT — a truncation is not a
                // completion.
                if (roundOutcome.outputLimitReached) {
                    val truncatedAssistant = messages.last().copy(
                        parts = messages.last().parts + UIMessagePart.Text(runKernelMessages.outputLimitNotice),
                    )
                    messages = messages.dropLast(1) + truncatedAssistant
                    emit(GenerationChunk.Messages(messages))
                    Log.i(TAG, "output limit reached; skipping the tool batch and finishing the run")
                    terminal = GenerationTerminal.OutputLimit
                    brokeEarly = true
                    break
                }

                val awaitingExecution = completedAssistant.getTools().filterNot { it.isExecuted }
                if (awaitingExecution.isEmpty()) {
                    // 下一步仍能调用工具（非 FINAL 预算阶段）时才续跑，否则续跑也无法运行检查。
                    if (completionCheck != null &&
                        !completionNudged &&
                        stepIndex < maxSteps - 1 &&
                        app.amber.feature.runtime.AgentLoopBudgetPrompt.stage(stepIndex + 1, maxSteps) !=
                        app.amber.feature.runtime.AgentLoopBudgetStage.FINAL &&
                        completionCheck.shouldContinue(messages, runId, ::toolOutputIsFailure)
                    ) {
                        completionNudged = true
                        // 以一条可见的用户消息续跑：请求以 user 收尾（不构成 assistant 预填），
                        // 续跑输出新开一条 assistant 消息；与 steer 并入同一路径。
                        messages = messages + UIMessage.user(runKernelMessages.completionCheckPrompt) + consumeSteerMessages()
                        emit(GenerationChunk.Messages(messages))
                        continue
                    }
                    brokeEarly = true
                    break
                }

                // P1-02 write-ahead: validate + digest + persist PREPARED before
                // approval is shown. Idempotent per (runId, toolCallId) — a
                // resumed approval round reuses the same effect. A prepare that
                // hits a protocol mismatch (the callId is already bound to a
                // different tool or args) fails closed: the call is collected
                // into [protocolFailures] with a structured error instead of
                // executing against the old binding.
                if (durablePath) {
                    val messageId = messages.lastOrNull()?.id?.toString()
                    val effects = LinkedHashMap<String, ToolEffect>()
                    val failures = mutableListOf<UIMessagePart.Tool>()
                    for (tool in awaitingExecution) {
                        val toolDef = toolsInternal.find { it.name == tool.toolName }
                        try {
                            val effect = toolEffectLedger.prepare(
                                runId = runId,
                                turnId = stepIndex,
                                toolCallId = tool.toolCallId,
                                toolName = tool.toolName,
                                input = tool.input,
                                effectClass = toolDef?.effectClass(tool.input) ?: ToolEffectClass.NON_IDEMPOTENT_WRITE,
                                messagePersistenceCursor = messageId,
                            )
                            effects[tool.toolCallId] = effect
                        } catch (e: ToolEffectProtocolMismatchException) {
                            Log.w(
                                TAG,
                                "tool effect protocol mismatch for ${tool.toolCallId}; failing the call closed",
                                e,
                            )
                            failures += tool.copy(
                                output = listOf(UIMessagePart.Text(protocolMismatchOutput(runKernelMessages))),
                            )
                        }
                    }
                    preparedEffects = effects
                    protocolFailures = failures
                }
                // Step 3: the tool lifecycle enters the protocol event stream
                // here, aligned with the ledger by effectId. Prepared fires
                // before approval is shown; Started/Finished come from the
                // dispatcher around execution. Durable path only — without a
                // ledger there is no effectId to align with.
                if (durablePath) {
                    preparedEffects.values.forEach { effect ->
                        session.toolLifecycleEvents?.commit(
                            app.amber.core.agent.runtime.ToolLifecycleEvent.Prepared(
                                effectId = effect.effectId,
                                toolCallId = effect.toolCallId,
                                toolName = effect.toolName,
                                effectClass = effect.effectClass.name,
                                argsDigest = effect.argsDigest,
                            ),
                        )
                    }
                }

                // Protocol-mismatched calls are settled immediately: their
                // structured error output joins the assistant turn now, so it
                // is in the conversation whichever way the batch ends (an
                // approval park must not swallow it). They are also carried in
                // [protocolFailures] into the batch results below, which keeps
                // the loop alive for a follow-up model round.
                if (protocolFailures.isNotEmpty()) {
                    val failedByCallId = protocolFailures.associateBy(UIMessagePart.Tool::toolCallId)
                    val settledAssistant = messages.last().copy(
                        parts = messages.last().parts.map { part ->
                            if (part is UIMessagePart.Tool) failedByCallId[part.toolCallId] ?: part else part
                        },
                    )
                    messages = messages.dropLast(1) + settledAssistant
                    emit(GenerationChunk.Messages(messages))
                }

                // Check for tools that need approval. Protocol-mismatched
                // calls are already settled (structured error, merged with the
                // batch results below) — they never reach the approval gate.
                val mismatchedCallIds = protocolFailures.map(UIMessagePart.Tool::toolCallId).toSet()
                val freshApproval = approvalPlanner.planFresh(
                    tools = awaitingExecution.filterNot { it.toolCallId in mismatchedCallIds },
                    toolDefinitions = toolsInternal,
                    preparedEffectIds = preparedEffects.mapValues { it.value.effectId },
                    recentUserTexts = messages.filter { it.role == MessageRole.USER }.takeLast(3).map { it.toText() },
                )
                val updatedTools = freshApproval.updatedTools

                if (updatedTools != awaitingExecution) {
                    val decisionsByCallId = updatedTools.associateBy(UIMessagePart.Tool::toolCallId)
                    val assistantTurn = messages.last()
                    val approvalSnapshot = assistantTurn.copy(
                        parts = assistantTurn.parts.map { part ->
                            if (part is UIMessagePart.Tool) decisionsByCallId[part.toolCallId] ?: part else part
                        },
                    )
                    messages = messages.dropLast(1) + approvalSnapshot
                    emit(GenerationChunk.Messages(messages))
                }

                if (freshApproval.hasPendingApproval) {
                    Log.i(TAG, "run paused for tool approval")
                    terminal = GenerationTerminal.WaitingUser
                    brokeEarly = true
                    break
                }

                toolsToProcess = updatedTools
            } else {
                Log.i(TAG, "run resuming ${pendingTools.size} decided tool calls")
                toolsToProcess = pendingTools
            }

            // Guard duplicate actions before execution, while allowing fresh
            // observation calls to see changes made since a previous step.
            val classification = toolCallHistory.classify(toolsToProcess)
            val stoppingTool = classification.stopping
            // Durable path: terminalize the write-ahead effects of the calls
            // the guard rejects. Skipped/stopped calls never reach the
            // dispatcher, so their PREPARED rows would otherwise never see
            // another transition (retention only sweeps terminal rows;
            // recovery deliberately leaves PREPARED alone for approval
            // parks). FAILED is terminal, retention-collectible, and never
            // counted by the FINISHED-only guard rebuild.
            if (durablePath) {
                failGuardRejectedEffects(
                    ledger = toolEffectLedger!!,
                    runId = runId!!,
                    preparedEffects = preparedEffects,
                    tools = classification.skipped,
                    errorCategory = "duplicate_skipped",
                )
                if (stoppingTool != null) {
                    failGuardRejectedEffects(
                        ledger = toolEffectLedger,
                        runId = runId,
                        preparedEffects = preparedEffects,
                        tools = listOf(stoppingTool),
                        errorCategory = "duplicate_stopped",
                    )
                }
            }
            // NOTE: the guard stop itself (guard note + GuardStopped terminal)
            // lands AFTER the batch below has settled, so the first occurrence
            // of the stopping signature executes and its result persists.

            // Handle tools (execute approved tools, handle denied tools).
            // Protocol-mismatched calls ride the same result channel as
            // guard-skipped ones: pre-settled outputs merged into the
            // assistant turn below, failure-class so the guard never counts
            // them, and the loop continues so the model can react. The batch
            // dispatches EVEN when the guard found a stopping call — the
            // first occurrence of the stopping signature is a legitimate
            // call ("本批首个执行"); the stop lands after its result settled.
            val toolDefinitionsForBatch = if (autoToolsReadyForDispatch.isNotEmpty()) {
                // Budget hiding applies to provider exposure only. The
                // already-persisted call still needs its current definition
                // for this local resume execution.
                toolDefinitionsForPending
            } else {
                toolsInternal.associateBy { it.name }
            }
            val rawExecutedTools = protocolFailures + classification.skipped + toolDispatcher.executeBatch(
                tools = classification.toExecute,
                toolDefinitions = toolDefinitionsForBatch,
                autoApproveTools = autoApproveTools,
                autoApproveHighRiskTools = autoApproveHighRiskTools,
                autoApprovedToolNames = autoApprovedToolNames,
                invocationContext = invocationContext,
                prefetchedTools = speculativeRunner?.reusableResults(toolsToProcess).orEmpty(),
                retrySetting = settings.generationRetry,
                ledgerContext = if (durablePath) {
                    ToolLedgerContext(
                        runId = runId!!,
                        turnId = stepIndex,
                        ledger = toolEffectLedger!!,
                        messagePersistenceCursor = messages.lastOrNull()?.id?.toString(),
                        events = session.toolLifecycleEvents,
                    )
                } else {
                    null
                },
                capabilityPermissions = capabilityState,
                approvalHistory = if (capabilityState != null) capabilityPermissionStore else null,
                permissionContext = permissionContext,
                executionPolicy = executionPolicy,
            )
            val executedTools = scriptJudgment?.annotate(rawExecutedTools, runId) ?: rawExecutedTools

            if (executedTools.isNotEmpty()) {
                toolExposure.observeExecutedTools(executedTools)

                toolCallHistory.recordSettled(executedTools)

                val resultsByCallId = executedTools.associateBy(UIMessagePart.Tool::toolCallId)
                val currentAssistant = messages.last()
                val mergedAssistant = currentAssistant.copy(
                    parts = currentAssistant.parts.map { part ->
                        if (part is UIMessagePart.Tool) resultsByCallId[part.toolCallId] ?: part else part
                    },
                )
                messages = messages.dropLast(1) + mergedAssistant
                val visibleToolResults = messages.transforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    settings = settings.transformers,
                )
                emit(GenerationChunk.Messages(visibleToolResults))
            }

            // The guard stop, settled after the batch: the loop ends with the
            // guard note and its own terminal — never a silent completion.
            if (stoppingTool != null) {
                val currentAssistant = messages.last()
                val stoppedAssistant = currentAssistant.copy(
                    parts = currentAssistant.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == stoppingTool.toolCallId) {
                            stoppingTool
                        } else {
                            part
                        }
                    } + UIMessagePart.Text(runKernelMessages.duplicateToolCallNotice),
                )
                messages = messages.dropLast(1) + stoppedAssistant
                emit(GenerationChunk.Messages(messages))
                Log.i(TAG, "duplicate tool call stopped the run (${stoppingTool.toolName} ${stoppingTool.toolCallId})")
                terminal = GenerationTerminal.GuardStopped(DUPLICATE_TOOL_CALL_GUARD_REASON)
                brokeEarly = true
                break
            }

            if (executedTools.isEmpty()) {
                // 当前不可达：带审批的批次已在上面 hasPendingApproval 处 break；
                // 恢复路径只含可恢复（可立即执行）的工具；被拒工具也会带上
                // denied 输出计入 executedTools。它与历史 bug 逐字同形——若
                // 未来移除先行守卫使本分支复活，必须按无进展回合收尾
                // （terminal 保持 null、brokeEarly），而不是误标 WaitingUser
                // 把不进展的循环伪装成等待用户。
                brokeEarly = true
                break
            }

            val steerMessages = consumeSteerMessages()
            if (steerMessages.isNotEmpty()) {
                messages = messages + steerMessages
                emit(GenerationChunk.Messages(messages))
            }
        }

        // The loop ran out of step budget without breaking: the model still
        // had work in flight — STEP_LIMIT, never COMPLETED.
        if (!brokeEarly && terminal == null) {
            terminal = GenerationTerminal.StepLimit
        }
        if (terminal != null) {
            runCatching { onTerminal?.invoke(terminal) }
                .onFailure { error -> Log.w(TAG, "onTerminal failed", error) }
        }

        }
    }.flowOn(Dispatchers.IO)

    /**
     * Structured output attached to a call whose write-ahead prepare hit a
     * ledger protocol mismatch (the toolCallId is already bound to a
     * different tool or args). "error" is failure-class: the call is never
     * executed and never counted by the duplicate guard.
     */
    private fun protocolMismatchOutput(runKernelMessages: RunKernelMessages): String = buildJsonObject {
        put("status", "error")
        put("reason", "tool_effect_protocol_mismatch")
        put(
            "message",
            runKernelMessages.protocolMismatchMessage,
        )
    }.toString()

    /**
     * Terminalizes the write-ahead effects of guard-rejected calls (skipped
     * and stopped duplicates, durable path only). Those calls never reach the
     * dispatcher, so without this their PREPARED rows would never transition
     * again: retention only sweeps terminal rows and recovery deliberately
     * leaves PREPARED alone ("re-enters approval/execution" — true for
     * approval parks, which break BEFORE classification, not for guard
     * rejects). Only PREPARED rows are failed — a FINISHED row that prepare
     * reused (the finished re-emission shape) stays untouched — and FAILED is
     * never counted by the FINISHED-only guard rebuild, so a failed skip does
     * not escalate future repeats. The resume path has no preparedEffects
     * entry (its prepare happened in a previous run() invocation), so the
     * lookup falls back to the ledger row bound to this runId.
     */
    private suspend fun failGuardRejectedEffects(
        ledger: ToolEffectLedger,
        runId: String,
        preparedEffects: Map<String, ToolEffect>,
        tools: List<UIMessagePart.Tool>,
        errorCategory: String,
    ) {
        for (tool in tools) {
            val effect = (preparedEffects[tool.toolCallId]
                ?: ledger.getByToolCallId(tool.toolCallId)?.takeIf { it.runId == runId })
                ?.takeIf { it.status == ToolEffectStatus.PREPARED }
                ?: continue
            runCatching { ledger.fail(effect.effectId, errorCategory) }
                .onFailure { error ->
                    Log.w(TAG, "guard-reject effect terminalization failed for ${effect.effectId}", error)
                }
        }
    }
}
