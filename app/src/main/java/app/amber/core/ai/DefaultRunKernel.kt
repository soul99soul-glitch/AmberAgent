package app.amber.core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import app.amber.feature.runtime.argsDigest
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolExposureState
import app.amber.feature.tools.effectClass
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.agent.BuildConfig
import kotlinx.serialization.decodeFromString
import java.util.Locale
import kotlin.time.Clock

private const val TAG = "RunKernel"
private const val PERF_TAG = "AmberChatPerf"

// Guard notes appended to the assistant turn when a kernel guard stops the
// loop. Both ride the plain-text channel; the run settles terminal
// (OUTPUT_LIMIT / GUARD_STOPPED via [GenerationTerminal]) — never COMPLETED —
// and the note persists with the messages.
private const val OUTPUT_LIMIT_NOTICE = "模型回复达到输出上限，请重试。"
private const val DUPLICATE_TOOL_CALL_NOTICE = "检测到重复的工具调用，已停止本轮以避免死循环。"

/** Wire reason carried by [GenerationTerminal.GuardStopped]. */
private const val DUPLICATE_TOOL_CALL_GUARD_REASON = "duplicate_tool_call"

/** Failure-class tool-output statuses — mirrors the UI failure classifier (ChatMessageTools.toolHasFailure). */
private val FAILURE_TOOL_STATUSES = setOf("failed", "error", "denied", "timed_out", "interrupted", "policy_denied")

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
        val toolExposure = ToolExposureState.from(session.tools)
        var terminal: GenerationTerminal? = null
        var brokeEarly = false

        // Duplicate-tool-call guard memory (aligned with the iOS ToolLoopGuard):
        // signature (toolName + argsDigest) → number of counted executions
        // this run, plus the toolCallIds already counted. On the durable path
        // the same runId can re-enter run() many times (approval round, crash
        // resume), so the tables are seeded from the write-ahead ledger —
        // FINISHED, non-failure effects of THIS runId are executions that
        // already happened, by the same standard the in-run counting below
        // applies.
        val executedSignatures = mutableMapOf<Pair<String, String>, Int>()
        val countedToolCallIds = mutableSetOf<String>()
        if (durablePath) {
            toolEffectLedger!!.listByRun(runId!!).forEach { effect ->
                if (effect.status != ToolEffectStatus.FINISHED) return@forEach
                if (effect.toolCallId in countedToolCallIds) return@forEach
                val output = ledgerEffectOutput(effect)
                if (output.isEmpty() || toolOutputIsFailure(output)) return@forEach
                countedToolCallIds += effect.toolCallId
                executedSignatures.merge(effect.toolName to effect.argsDigest, 1, Int::plus)
            }
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
            val waitingTools = messages.lastOrNull()?.getTools()?.filter { it.isPending }.orEmpty()
            if (waitingTools.isNotEmpty()) {
                terminal = GenerationTerminal.WaitingUser
                brokeEarly = true
                break
            }
            toolExposure.exposeToolNames(pendingTools.map { it.toolName })
            val hasResumableTools = pendingTools.isNotEmpty()
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
                        toolExposure.toolsForStep()
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
                settings.agentRuntime.speculativeToolExecution.enabled &&
                settings.streamOutput
            ) {
                SpeculativeToolRunner(
                    scope = this,
                    dispatcher = toolDispatcher,
                    maxConcurrentTools = settings.agentRuntime.speculativeToolExecution.maxConcurrentTools,
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

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
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
                        responsesResume = responsesResume,
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
                            settings = settings
                        )
                        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        val visualMessages = if (update.isStreamingTail && streamingVisualBaselineReady) {
                            messages.visualTransformsStreamingTail(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                settings = settings
                            )
                        } else {
                            streamingVisualBaselineReady = true
                            messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                settings = settings
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
                    settings = settings
                ).onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    settings = settings
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
                        parts = messages.last().parts + UIMessagePart.Text(OUTPUT_LIMIT_NOTICE),
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
                                effectClass = toolDef?.effectClass() ?: ToolEffectClass.NON_IDEMPOTENT_WRITE,
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
                                output = listOf(UIMessagePart.Text(protocolMismatchOutput())),
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
                var hasPendingApproval = false
                val updatedTools = awaitingExecution
                    .filterNot { it.toolCallId in mismatchedCallIds }
                    .map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    val decision = toolDispatcher.resolveDecision(
                        toolDef = toolDef,
                        tool = tool,
                        autoApproveTools = autoApproveTools,
                        autoApproveHighRiskTools = autoApproveHighRiskTools,
                        autoApprovedToolNames = autoApprovedToolNames,
                        invocationContext = invocationContext,
                        capabilityPermissions = capabilityState,
                        permissionContext = permissionContext,
                    )
                    val ledgerMetadata = preparedEffects[tool.toolCallId]?.let { effect ->
                        buildJsonObject {
                            put("effect_id", effect.effectId)
                            put("run_id", runId)
                        }
                    } ?: JsonObject(emptyMap())
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        decision.action == app.amber.feature.runtime.PermissionDecisionAction.ASK -> {
                            hasPendingApproval = true
                            tool.copy(
                                approvalState = ToolApprovalState.Pending,
                                metadata = mergeToolMetadata(tool.metadata, decision.trace.toJson(), ledgerMetadata),
                            )
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> if (ledgerMetadata.isEmpty()) {
                            tool
                        } else {
                            tool.copy(metadata = mergeToolMetadata(tool.metadata, null, ledgerMetadata))
                        }
                    }
                }

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

                if (hasPendingApproval) {
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

            // Duplicate-tool-call guard (aligned with the iOS ToolLoopGuard):
            // classify this step's calls against the in-memory signature
            // table BEFORE anything executes — 1st occurrence runs, 2nd is
            // skipped with a structured reminder the model sees through the
            // tool result, 3rd stops the loop entirely.
            val classification = classifyDuplicates(toolsToProcess, executedSignatures)
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
            val executedTools = protocolFailures + classification.skipped + toolDispatcher.executeBatch(
                tools = classification.toExecute,
                toolDefinitions = toolsInternal.associateBy { it.name },
                autoApproveTools = autoApproveTools,
                autoApproveHighRiskTools = autoApproveHighRiskTools,
                autoApprovedToolNames = autoApprovedToolNames,
                invocationContext = invocationContext,
                prefetchedTools = speculativeRunner?.reusableResults(toolsToProcess).orEmpty(),
                retrySetting = settings.agentRuntime.generationRetry,
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

            if (executedTools.isNotEmpty()) {
                toolExposure.observeExecutedTools(executedTools)

                // Feed the duplicate guard table: every settled non-failure
                // result counts as one occurrence — including guard-skipped
                // duplicates (their structured "skipped" output is not
                // failure-class), so the signature table sees execute AND
                // skip as occurrences and the stop gate fires on the third
                // occurrence even when the second was never executed. The
                // same toolCallId re-running (approval resume, ledger reuse)
                // is the same emission, never a second count; failure-class
                // outputs (same standard as the UI failure classifier) and
                // user/policy denials don't count either, so a failed or
                // denied call may retry with identical args.
                for (tool in executedTools) {
                    if (tool.toolCallId in countedToolCallIds) continue
                    if (tool.output.isEmpty() || toolOutputIsFailure(tool.output)) continue
                    countedToolCallIds += tool.toolCallId
                    executedSignatures.merge(tool.toolName to argsDigest(tool.input), 1, Int::plus)
                }

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
                    settings = settings,
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
                    } + UIMessagePart.Text(DUPLICATE_TOOL_CALL_NOTICE),
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
     * Merges existing tool metadata with the permission trace and the ledger
     * binding (effect_id / run_id). Ledger keys win — the effect binding is
     * authoritative for the approval card.
     */
    private fun mergeToolMetadata(
        existing: JsonObject?,
        permissionTrace: JsonObject?,
        ledger: JsonObject,
    ): JsonObject {
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        existing?.forEach { (key, value) -> merged[key] = value }
        if (permissionTrace != null) merged["permission_trace"] = permissionTrace
        ledger.forEach { (key, value) -> merged[key] = value }
        return JsonObject(merged)
    }

    /**
     * Classifies one step's tool calls against the duplicate-signature table,
     * in batch order. The occurrence of each call is its run-level count
     * (counted occurrences of the same signature this run — settled
     * executions AND guard-skipped repeats alike, since a skip is itself an
     * occurrence) plus the same-batch twins already seen, plus one:
     * occurrence 1 goes to [Duplicates.toExecute], occurrence 2 to
     * [Duplicates.skipped] with a structured reminder, and occurrence >= 3
     * makes the call [Duplicates.stopping] and ends the scan — the loop
     * breaks right after, so later calls of the same step stay untouched.
     *
     * The batch-local counter (this step only — never merged into
     * [executedSignatures]) exists because the signature table is fed AFTER
     * executeBatch: two same-signature calls inside one assistant message
     * would both read a null count here and both execute — non-idempotent
     * side effects would run twice. Counting per-occurrence (not just
     * seen/not-seen) makes the third same-signature call inside ONE batch a
     * stop, the same as the third across steps: first executes, second is
     * skipped, third stops the run.
     *
     * There is deliberately NO per-toolCallId exemption anymore: a counted
     * callId re-entering the guard is routed through the signature table like
     * any other call. Approval/crash resume never needs an exemption — those
     * flows re-enter run() with PREPARED/RECONCILED effects, which are never
     * counted (only FINISHED, non-failure executions count), so a resumed
     * emission reaches the table with a null count and executes. A FINISHED
     * callId re-emitted by the model is a genuine repeat and must not
     * unconditionally re-execute.
     */
    private class Duplicates(
        val toExecute: List<UIMessagePart.Tool>,
        val skipped: List<UIMessagePart.Tool>,
        val stopping: UIMessagePart.Tool?,
    )

    private fun classifyDuplicates(
        tools: List<UIMessagePart.Tool>,
        executedSignatures: Map<Pair<String, String>, Int>,
    ): Duplicates {
        val toExecute = mutableListOf<UIMessagePart.Tool>()
        val skipped = mutableListOf<UIMessagePart.Tool>()
        var stopping: UIMessagePart.Tool? = null
        val batchSeenCounts = mutableMapOf<Pair<String, String>, Int>()
        for (tool in tools) {
            val signature = tool.toolName to argsDigest(tool.input)
            val batchSeen = batchSeenCounts[signature] ?: 0
            val occurrence = (executedSignatures[signature] ?: 0) + batchSeen + 1
            when {
                occurrence == 1 -> {
                    batchSeenCounts[signature] = batchSeen + 1
                    toExecute += tool
                }
                // Second occurrence: a same-batch twin of a call executing in
                // this very batch, or the cross-step repeat of an already
                // counted execution — either way it never reaches
                // executeBatch.
                occurrence == 2 -> {
                    batchSeenCounts[signature] = batchSeen + 1
                    skipped += tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                duplicateSkipOutput(
                                    occurrence = 2,
                                    message = if (batchSeen > 0) {
                                        "同一批工具调用中已出现相同参数的调用，本次同批重复调用被跳过；请基于首个调用的结果继续，不要再次调用。"
                                    } else {
                                        "该工具已用相同参数成功执行过，本次重复调用被跳过；请基于已有结果继续，不要再次调用。"
                                    },
                                ),
                            ),
                        ),
                    )
                }
                else -> {
                    stopping = tool.copy(
                        output = listOf(UIMessagePart.Text(duplicateSkipOutput(occurrence = occurrence))),
                    )
                    break
                }
            }
        }
        return Duplicates(toExecute, skipped, stopping)
    }

    /**
     * Structured output attached to a guard-skipped duplicate call. `skipped`
     * is an established status (RecipeRunner, UI classification) — outside the
     * failure set, so the model reads the reminder and self-corrects.
     * [message] distinguishes the same-batch twin (never executed at all)
     * from the cross-step repeat (already executed with identical args).
     */
    private fun duplicateSkipOutput(
        occurrence: Int,
        message: String = "该工具已用相同参数成功执行过，本次重复调用被跳过；请基于已有结果继续，不要再次调用。",
    ): String = buildJsonObject {
        put("status", "skipped")
        put("reason", "duplicate_tool_call")
        put("occurrence", occurrence)
        put("message", message)
    }.toString()

    /**
     * Structured output attached to a call whose write-ahead prepare hit a
     * ledger protocol mismatch (the toolCallId is already bound to a
     * different tool or args). "error" is failure-class: the call is never
     * executed and never counted by the duplicate guard.
     */
    private fun protocolMismatchOutput(): String = buildJsonObject {
        put("status", "error")
        put("reason", "tool_effect_protocol_mismatch")
        put(
            "message",
            "此 tool_call_id 已绑定过不同的工具或参数，本次调用被拒绝且未执行；请改用新的 tool_call_id 重新发起调用。",
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

    /**
     * Whether a tool's structured output is failure-class — the SAME standard
     * as the UI failure classifier (ChatMessageTools.toolHasFailure): a status
     * in its failure set, or a non-blank `error` field. Plain-text or
     * unparseable output is not failure-class.
     */
    private fun toolOutputIsFailure(output: List<UIMessagePart>): Boolean {
        val obj = output.filterIsInstance<UIMessagePart.Text>()
            .firstNotNullOfOrNull { part ->
                runCatching { Json.parseToJsonElement(part.text).jsonObject }.getOrNull()
            } ?: return false
        val status = runCatching { obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase() }.getOrNull()
        if (status != null && status in FAILURE_TOOL_STATUSES) return true
        val error = runCatching { obj["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return !error.isNullOrBlank()
    }

    /**
     * Best-effort reconstruction of a FINISHED effect's output for the guard
     * rebuild: decode the persisted payload, falling back to the retained
     * summary as plain text (the payload is dropped once the result has landed
     * in the conversation). An empty result counts as "no output" — the same
     * not-counted treatment the in-run rule gives empty outputs.
     */
    private fun ledgerEffectOutput(effect: ToolEffect): List<UIMessagePart> {
        val payload = effect.resultPayload
        if (payload != null) {
            runCatching { Json.decodeFromString<List<UIMessagePart>>(payload) }.getOrNull()?.let { return it }
        }
        return effect.resultSummary?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList()
    }
}
