package app.amber.core.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.MessageRole
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_CONTROL_METADATA
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_EPHEMERAL
import app.amber.ai.core.merge
import app.amber.ai.provider.Modality
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.handleMessageChunk
import app.amber.ai.util.ImageEncodingException
import app.amber.core.ai.transformers.transforms
import app.amber.core.ai.transformers.OcrStrings
import app.amber.core.ai.generative.GenerativeUiPlanner
import app.amber.core.ai.generative.GenerativeUiWidgetRequirement
import app.amber.core.ai.generative.GenerativeWidgetCopy
import app.amber.core.ai.generative.GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS
import app.amber.core.ai.generative.GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS
import app.amber.core.ai.generative.GenerativeUiReasoningOnlyStreamException
import app.amber.core.ai.generative.GenerativeUiInvalidWidgetStreamException
import app.amber.core.ai.generative.GenerativeUiRepairPolicy.hasVisibleWidgetFence
import app.amber.core.ai.generative.GenerativeUiRepairPolicy.visibleWidgetIssue
import app.amber.core.ai.generative.GenerativeUiRepairPolicy.hasMeaningfulVisibleAssistantText
import app.amber.core.ai.generative.GenerativeUiRepairPolicy.withGenerativeUiVisibleFallbackPrompt
import app.amber.core.ai.generative.GenerativeUiRepairPolicy.withLocalGenerativeUiFallbackWidget
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextPlanner
import app.amber.core.utils.appLocale
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.repository.ConversationRepository
import app.amber.agent.BuildConfig
import app.amber.agent.R
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val TAG = "GenerationRoundEngine"
private const val PERF_TAG = "AmberChatPerf"
// 2026-05-15 — flush cadence rationale.
//
// Was 200ms historically. User feedback after that: "一坨一坨蹦出来", not
// "一个字一个字蹦出来 like Claude Code CLI". Tried 16ms in 1.8.12 — fixed
// the chunk-reveal feel but user reported "往上滚动的帧数好像变低了". Root
// cause of the regression: 16ms means up to 60 Compose recompose +
// Markdown parse schedule + LazyColumn item re-layout + animateScrollBy
// cancel-and-restart cycles per second. Even with Markdown parse off the
// main thread (mapLatest on Dispatchers.Default), the recompose+layout
// chain itself eats enough budget on a mid-range device to choke user
// gesture scroll and the streaming auto-scroll animation.
//
// 33ms (~30Hz) is the sweet spot:
//   - SSE chunks from real providers arrive every 50-200ms (5-20 tokens
//     each on the fast end). 33ms is below that floor, so a chunk-arrival
//     reaches the UI on the very next 33ms tick — practically immediate.
//     The only case where 33ms differs from 16ms is when two chunks land
//     <33ms apart, which is rare and not perceptually distinguishable
//     anyway (one tick visually = one font row).
//   - Halves the per-second recompose budget vs 16ms, freeing CPU for
//     scroll animation interpolation and gesture handling.
//
// If a future profile shows this is still too aggressive, the next stop
// is 50ms. Do not tie this producer-side flush to 120Hz: high-refresh
// smoothness should come from the lightweight display/reveal layer, while
// Markdown parse/layout stays on a lower-frequency budget.
private const val STREAM_UI_FLUSH_INTERVAL_MS = 33L
/**
 * The production [GenerationRoundEngine]: one model round end-to-end —
 * session defaults, memory recall, system-prompt assembly, context-engine
 * preparation, input transforms, provider streaming/completion with retry,
 * and the Generative-UI / vision fallbacks.
 *
 * The loop policy around the round (budget, exposure, approval, ledger,
 * dispatch) lives in [DefaultRunKernel]; this engine never makes loop
 * decisions.
 */
class ChatGenerationRoundEngine(
    private val context: Context,
    private val providerCatalog: ProviderCatalog,
    private val json: Json,
    private val memoryRecallStore: MemoryRecallStore,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val conversationContextEngine: ConversationContextEngine,
    private val agentSoulStore: app.amber.feature.prompts.AgentSoulStore? = null,
) : GenerationRoundEngine {

    override suspend fun generateRound(
        request: GenerationRoundRequest,
        onUpdateMessages: suspend (GenerationUpdate) -> Unit,
    ): GenerationRoundOutcome {
        val settings = request.settings
        val transformers = request.transformers
        val model = request.model
        val provider = settings.provider ?: error("Provider not found")
        val providerImpl = providerCatalog.text(provider)
        val tools = request.tools
        val memories = request.memories
        val stream = request.stream
        val processingStatus = request.processingStatus
        val conversation = request.conversation
        val speculativeRunner = request.speculativeRunner
        val loopBudgetPrompt = request.loopBudgetPrompt
        val responsesResume = request.responsesResume
        val perfEnabled = BuildConfig.DEBUG && runCatching { Log.isLoggable(PERF_TAG, Log.DEBUG) }.getOrDefault(false)
        val ocrStrings = OcrStrings.from(context)
        val generativeWidgetCopy = GenerativeWidgetCopy.from(context)

        var messages: List<UIMessage> = request.messages
        val sessionDefaults = settings.defaults
        val memoryStartedAt = if (perfEnabled) System.nanoTime() else 0L
        val memoryContextPrompt = memoryRecallStore.buildPrompt(
            settings = settings.memory,
            messages = messages,
            locale = context.appLocale(),
            runKey = request.runId,
        )
        if (perfEnabled) {
            Log.d(PERF_TAG, "round.memory step=${request.stepIndex} elapsedMs=${(System.nanoTime() - memoryStartedAt) / 1_000_000.0}")
        }
        val systemParts = buildSystemPromptParts(
            settings = settings,
            model = model,
            messages = messages,
            tools = tools,
            memoryContextPrompt = memoryContextPrompt,
            loopBudgetPrompt = loopBudgetPrompt,
        )
        val system = systemParts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }
        val contextStartedAt = if (perfEnabled) System.nanoTime() else 0L
        val preparedContext = conversationContextEngine.prepareContext(
            conversation = conversation,
            settings = settings.context,
            model = model,
            messages = messages,
            tools = tools,
            contextMessageSize = sessionDefaults.contextMessageSize,
            promptOverheadTokens = ConversationContextPlanner.estimateTokens(listOf(UIMessage.system(system))),
            jevRunKey = request.runId,
        )
        if (perfEnabled) {
            Log.d(PERF_TAG, "round.prepareContext step=${request.stepIndex} messages=${preparedContext.messages.size} elapsedMs=${(System.nanoTime() - contextStartedAt) / 1_000_000.0}")
        }
        suspend fun prepareInternalMessages(forceImageToText: Boolean = false): List<UIMessage> =
            buildList {
                if (systemParts.isNotEmpty()) add(UIMessage(role = MessageRole.SYSTEM, parts = systemParts))
                addAll(preparedContext.messages)
            }.transforms(
                transformers = transformers,
                context = context,
                model = model,
                settings = settings.transformers,
                processingStatus = processingStatus,
                forceImageToText = forceImageToText,
            )

        val internalMessages = prepareInternalMessages()
        val canUseVisionFallback = model.inputModalities.contains(Modality.IMAGE) && internalMessages.hasImageParts()

        val attempts = ProviderAttemptExecutor(
            model = model,
            provider = provider,
            gateway = providerImpl,
            json = json,
            conversationId = conversation?.id?.toString(),
            systemParts = systemParts,
            runId = request.runId,
            stepIndex = request.stepIndex,
            events = request.events,
            durablePath = request.durablePath,
            perfEnabled = perfEnabled,
        )

        val baseMessages: List<UIMessage> = messages
        messages = baseMessages
        val params = TextGenerationParams(
            model = model,
            temperature = settings.temperature,
            topP = settings.topP,
            maxTokens = sessionDefaults.maxTokens,
            tools = tools,
            reasoningLevel = sessionDefaults.reasoningLevel,
            customHeaders = buildList {
                addAll(settings.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(settings.customBodies)
                addAll(model.customBodies)
            },
            responsesResume = responsesResume,
            // Stable conversation owner; standalone calls get a fresh UUID here.
            sessionId = conversation?.id?.toString() ?: Uuid.random().toString(),
        )
        val generativeUiWidgetRequirement =
            GenerativeUiPlanner.widgetRequirement(settings.generativeUi, messages)
        val shouldGuardGenerativeUiReasoningOnly =
            GenerativeUiPlanner.needsVisibleStreamingFallback(settings.generativeUi, messages)
        if (stream) {
            runProviderCallWithRetry(
                retrySetting = settings.generationRetry,
                processingStatus = processingStatus,
                providerName = provider.name,
                modelName = model.displayName,
                onBeforeRetry = {
                    messages = baseMessages
                    onUpdateMessages(GenerationUpdate.full(messages))
                },
            ) {
                aiLoggingManager.addLog(
                    AILogging.Generation(
                        params = params,
                        messages = baseMessages,
                        providerSetting = provider,
                        stream = true
                    )
                )
                suspend fun streamWith(
                    providerMessages: List<UIMessage>,
                    streamParams: TextGenerationParams,
                    guardReasoningOnly: Boolean,
                    widgetRequirement: GenerativeUiWidgetRequirement,
                    kind: String,
                ) {
                    val chunks = attempts.stream(providerMessages, streamParams, kind)
                    val accumulator = MessageStreamAccumulator(baseMessages, model)
                    var lastFlushAt = 0L
                    val streamStartedAt = System.currentTimeMillis()
                    var visibleTextChars = 0
                    var reasoningChars = 0
                    chunks.collect { chunk ->
                        val deltaParts = chunk.choices.getOrNull(0)?.let { choice ->
                            choice.delta?.parts ?: choice.message?.parts
                        }.orEmpty()
                        visibleTextChars += deltaParts
                            .filterIsInstance<UIMessagePart.Text>()
                            .sumOf { it.text.length }
                        reasoningChars += deltaParts
                            .filterIsInstance<UIMessagePart.Reasoning>()
                            .sumOf { it.reasoning.length }
                        accumulator.append(chunk)
                        val now = System.currentTimeMillis()
                        if (lastFlushAt == 0L || now - lastFlushAt >= STREAM_UI_FLUSH_INTERVAL_MS) {
                            messages = accumulator.snapshot()
                            speculativeRunner?.observe(
                                messages.lastOrNull()?.getTools().orEmpty(),
                                tools.associateBy { it.name }
                            )
                            onUpdateMessages(GenerationUpdate.streamingTail(messages))
                            lastFlushAt = now
                        }
                        if (
                            guardReasoningOnly &&
                            visibleTextChars == 0 &&
                            reasoningChars > 0 &&
                            (
                                reasoningChars >= GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS ||
                                    now - streamStartedAt >= GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS
                                )
                        ) {
                            throw GenerativeUiReasoningOnlyStreamException()
                        }
                    }
                    messages = accumulator.snapshot()
                    speculativeRunner?.observe(
                        messages.lastOrNull()?.getTools().orEmpty(),
                        tools.associateBy { it.name }
                    )
                    onUpdateMessages(GenerationUpdate.full(messages))
                    if (widgetRequirement.required && !messages.hasPendingToolCalls()) {
                        messages.visibleWidgetIssue(widgetRequirement, generativeWidgetCopy)?.let { issue ->
                            throw GenerativeUiInvalidWidgetStreamException(issue)
                        }
                    }
                }

                try {
                    streamWith(
                        providerMessages = internalMessages,
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        widgetRequirement = generativeUiWidgetRequirement,
                        kind = "primary",
                    )
                } catch (error: Throwable) {
                    if (
                        error is GenerativeUiReasoningOnlyStreamException ||
                        error is GenerativeUiInvalidWidgetStreamException
                    ) {
                        val widgetIssue = (error as? GenerativeUiInvalidWidgetStreamException)?.issue
                        messages = baseMessages
                        onUpdateMessages(GenerationUpdate.full(messages))
                        processingStatus.value = if (generativeUiWidgetRequirement.expectSlides) {
                            generativeWidgetCopy.repairingPresentation
                        } else {
                            generativeWidgetCopy.switchingToVisibleOutput
                        }
                        // Streaming speculative execution may already have started a
                        // side-effectful tool before the widget guard rejects this
                        // attempt. Do not expose the same tools on the fallback retry;
                        // keep the user's reasoning level unchanged.
                        streamWith(
                            providerMessages = internalMessages.withGenerativeUiVisibleFallbackPrompt(
                                requirement = generativeUiWidgetRequirement,
                                previousIssue = widgetIssue,
                            ),
                            streamParams = params.copy(tools = emptyList()),
                            guardReasoningOnly = false,
                            widgetRequirement = GenerativeUiWidgetRequirement.None,
                            kind = "generative_ui_repair",
                        )
                        // Only force-inject the local fallback widget when the retry
                        // ALSO produced no meaningful visible text. Previously we
                        // injected unconditionally on "no widget fence", which meant a
                        // model that legitimately decided "this question doesn't need a
                        // visualisation, here's a normal answer" had a synthetic
                        // skeleton widget appended to its perfectly good text — visible
                        // as the "widget keeps appearing on every reply" loop the user
                        // reported.
                        val retryIssue = messages.visibleWidgetIssue(
                            generativeUiWidgetRequirement,
                            generativeWidgetCopy,
                        )
                        val shouldAppendLocalFallback = if (generativeUiWidgetRequirement.required) {
                            retryIssue != null && !messages.hasPendingToolCalls()
                        } else {
                            !messages.hasVisibleWidgetFence(generativeWidgetCopy) &&
                                !messages.hasMeaningfulVisibleAssistantText()
                        }
                        if (shouldAppendLocalFallback) {
                            messages = messages.withLocalGenerativeUiFallbackWidget(
                                baseMessages = baseMessages,
                                model = model,
                                requirement = generativeUiWidgetRequirement,
                            )
                            onUpdateMessages(GenerationUpdate.full(messages))
                        }
                        return@runProviderCallWithRetry
                    }
                    if (!canUseVisionFallback || !shouldFallbackToVisionRecognition(error)) throw error
                    processingStatus.value = ocrStrings.visionFallback
                    streamWith(
                        providerMessages = prepareInternalMessages(forceImageToText = true),
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        widgetRequirement = generativeUiWidgetRequirement,
                        kind = "vision_fallback",
                    )
                }
            }
        } else {
            runProviderCallWithRetry(
                retrySetting = settings.generationRetry,
                processingStatus = processingStatus,
                providerName = provider.name,
                modelName = model.displayName,
                onBeforeRetry = {
                    messages = baseMessages
                    onUpdateMessages(GenerationUpdate.full(messages))
                },
            ) {
                aiLoggingManager.addLog(
                    AILogging.Generation(
                        params = params,
                        messages = baseMessages,
                        providerSetting = provider,
                        stream = false
                    )
                )
                val chunk = try {
                    attempts.complete(
                        messages = internalMessages,
                        params = params,
                        kind = "primary",
                    )
                } catch (error: Throwable) {
                    if (!canUseVisionFallback || !shouldFallbackToVisionRecognition(error)) throw error
                    processingStatus.value = ocrStrings.visionFallback
                    attempts.complete(
                        messages = prepareInternalMessages(forceImageToText = true),
                        params = params,
                        kind = "vision_fallback",
                    )
                }
                messages = baseMessages.handleMessageChunk(chunk = chunk, model = model)
                chunk.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(
                                usage = message.usage.merge(usage)
                            )
                        } else {
                            message
                        }
                    }
                }
                val widgetIssue = messages.visibleWidgetIssue(
                    generativeUiWidgetRequirement,
                    generativeWidgetCopy,
                )
                if (widgetIssue != null && !messages.hasPendingToolCalls()) {
                    processingStatus.value = if (generativeUiWidgetRequirement.expectSlides) {
                        generativeWidgetCopy.repairingPresentation
                    } else {
                        generativeWidgetCopy.switchingToVisibleOutput
                    }
                    // The first non-streaming response can already contain tool calls;
                    // the fallback is a visible-output repair, not a second tool turn.
                    val retryChunk = attempts.complete(
                        messages = internalMessages.withGenerativeUiVisibleFallbackPrompt(
                            requirement = generativeUiWidgetRequirement,
                            previousIssue = widgetIssue,
                        ),
                        params = params.copy(tools = emptyList()),
                        kind = "generative_ui_repair",
                    )
                    messages = baseMessages.handleMessageChunk(chunk = retryChunk, model = model)
                    retryChunk.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(
                                    usage = message.usage.merge(usage)
                                )
                            } else {
                                message
                            }
                        }
                    }
                    if (
                        messages.visibleWidgetIssue(
                            generativeUiWidgetRequirement,
                            generativeWidgetCopy,
                        ) != null &&
                        !messages.hasPendingToolCalls()
                    ) {
                        messages = messages.withLocalGenerativeUiFallbackWidget(
                            baseMessages = baseMessages,
                            model = model,
                            requirement = generativeUiWidgetRequirement,
                        )
                    }
                }
                onUpdateMessages(GenerationUpdate.full(messages))
            }
        }
        return GenerationRoundOutcome(outputLimitReached = attempts.outputLimitReached)
    }

    private suspend fun buildSystemPromptParts(
        settings: GenerationSettings,
        model: app.amber.ai.provider.Model,
        messages: List<UIMessage>,
        tools: List<app.amber.ai.core.Tool>,
        memoryContextPrompt: String,
        loopBudgetPrompt: String,
    ): List<UIMessagePart> {
        val staticPrompt = listOf(
            buildAgentSoulPrompt(settings.agentSoulMarkdown),
            settings.systemPrompt,
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        val dynamicPrompt = buildList {
            if (
                agentSoulStore != null &&
                (settings.agentSoulMarkdown.isNotBlank() || tools.any { it.name == "agent_soul_update" })
            ) {
                add(buildAgentPersonaPrompt(agentSoulStore.state.value))
            }
            if (memoryContextPrompt.isNotBlank()) add(memoryContextPrompt)
            if (loopBudgetPrompt.isNotBlank()) add(loopBudgetPrompt)
            buildGenerativeUiPrompt(settings.generativeUi, model).takeIf { it.isNotBlank() }?.let(::add)
            val hasImageGenTool = tools.any { it.name == "generate_image" }
            GenerativeUiPlanner.buildPrompt(
                setting = settings.generativeUi,
                messages = messages,
                hasImageGenTool = hasImageGenTool,
                settingsPath = context.getString(R.string.setting_page_model_and_services) +
                    " → " + context.getString(R.string.setting_model_page_image_gen_model),
            ).takeIf { it.isNotBlank() }?.let(::add)
            if (settings.enableRecentChatsReference) {
                add(buildRecentChatsPrompt(conversationRepo, context.appLocale()))
            }
        }.joinToString("\n\n")

        val toolPrompt = tools.mapNotNull { tool ->
            tool.systemPrompt(model, messages).takeIf { it.isNotBlank() }
        }.joinToString("\n\n")

        return buildList {
            if (staticPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = staticPrompt,
                        metadata = buildJsonObject {
                            put(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_EPHEMERAL)
                            put("system_prompt_block", "static")
                        },
                    )
                )
            }
            if (dynamicPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = dynamicPrompt,
                        metadata = buildJsonObject { put("system_prompt_block", "dynamic") },
                    )
                )
            }
            if (toolPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = toolPrompt,
                        metadata = buildJsonObject { put("system_prompt_block", "tool_prompts") },
                    )
                )
            }
        }
    }

    private suspend fun runProviderCallWithRetry(
        retrySetting: GenerationRetrySetting,
        processingStatus: MutableStateFlow<String?>,
        providerName: String,
        modelName: String,
        onBeforeRetry: suspend () -> Unit,
        block: suspend () -> Unit,
    ) {
        var retryAttempt = 1
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                block()
                processingStatus.value = null
                return
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val decision = GenerationFailureClassifier.decide(
                    error = error,
                    attempt = retryAttempt,
                    setting = retrySetting,
                )
                if (!decision.retryable) {
                    processingStatus.value = null
                    throw error
                }
                onBeforeRetry()
                val seconds = (decision.delayMs / 1_000L).coerceAtLeast(1L)
                val status = context.getString(
                    R.string.generation_retry_status,
                    seconds,
                    retryAttempt,
                    retrySetting.maxRetries,
                    decision.reason,
                )
                processingStatus.value = status
                Log.w(
                    TAG,
                    "Provider call failed; retrying $retryAttempt/${retrySetting.maxRetries} " +
                        "after ${decision.delayMs}ms category=${decision.category} provider=$providerName model=$modelName",
                    error,
                )
                delay(decision.delayMs)
                retryAttempt++
            }
        }
    }

    private fun List<UIMessage>.hasImageParts(): Boolean =
        any { message -> message.parts.any { it is UIMessagePart.Image && it.url.isNotBlank() } }

    private fun List<UIMessage>.hasPendingToolCalls(): Boolean =
        lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.getTools()
            ?.any { !it.isExecuted } == true

    private fun shouldFallbackToVisionRecognition(error: Throwable): Boolean {
        if (error is ImageEncodingException) return true
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return listOf(
            "image",
            "vision",
            "modalit",
            "unsupported url",
            "unsupported file",
            "invalid file",
            "invalid mime",
            "decode",
            "base64"
        ).any { it in message }
    }
}
