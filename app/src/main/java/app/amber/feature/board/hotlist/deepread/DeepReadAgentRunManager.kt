package app.amber.feature.board.hotlist.deepread

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.ui.UIMessage
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.feature.board.hotlist.HotListRepository
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.subagent.toIsolatedSubAgentSettings
import app.amber.feature.tools.AgentToolSetFactory
import app.amber.feature.tools.DeepReadToolDescriptionContext
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.agent.runtime.AgentEventWriter
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class DeepReadAgentRunManager(
    private val appContext: Context,
    private val settingsStore: SettingsAggregator,
    private val kernel: RunKernel,
    private val hotListRepository: HotListRepository,
    private val toolSetFactory: AgentToolSetFactory,
    private val sourcePrefetcher: DeepReadSourcePrefetcher,
    private val playbookRepository: DeepReadPlaybookRepository,
    private val researchHarness: DeepReadResearchHarness,
    private val appScope: AppScope,
    private val artifactRepository: ArtifactRepository? = null,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundRuns = ConcurrentHashMap.newKeySet<String>()

    private data class DeepReadRunContext(
        val settings: Settings,
        val hiddenSettings: Settings,
        val model: Model,
        val topicTitle: String,
        val seedUrl: String?,
        val evidencePack: DeepReadEvidencePack,
        val evidenceRegistry: DeepReadEvidenceRegistry,
        val articlePlan: DeepReadArticlePlan,
        val playbookMarkdown: String,
        val writer: DeepReadSectionWriterTools,
        val locale: Locale,
        // Step 5: the run scope's identity + protocol event writer, threaded
        // from the DeepRead agent handler so kernel rounds can leave a durable
        // audit trail. Null on bare/background callers (no run scope).
        val runId: String?,
        val events: AgentEventWriter?,
    )

    suspend fun runPreview(
        topicTitle: String,
        seedUrl: String,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> {
        val normalizedSeedUrl = seedUrl.takeIf { it.isHttpOrHttpsUrl() }
            ?: return Result.failure(
                IllegalArgumentException(appContext.getString(R.string.deep_read_demo_failed))
            )
        val topicId = previewTopicId(normalizedSeedUrl)
        return topicMutex(topicId).withLock {
            try {
                hotListRepository.clearDeepRead(topicId)
                generateStages(
                    topicId = topicId,
                    topicTitle = topicTitle,
                    stages = DeepReadGenerationStage.entries,
                    seedUrl = normalizedSeedUrl,
                    force = true,
                    locale = locale,
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { hotListRepository.clearDeepRead(topicId) }
                }
            }
        }
    }

    suspend fun run(
        topicId: String,
        topicTitle: String,
        force: Boolean = false,
        seedUrl: String? = null,
        deferMissingStages: Boolean = true,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> = topicMutex(topicId).withLock {
        if (force) hotListRepository.clearDeepRead(topicId)

        val cached = if (force) null else fresh(topicId, topicTitle, seedUrl)
        if (cached?.isComplete() == true) {
            persistCompletedArtifact(topicId, topicTitle, cached)
            return@withLock Result.success(cached)
        }

        val missing = missingStages(cached ?: DeepReadOutput())
        if (shouldDeferDeepReadMissingStages(force, cached, missing, deferMissingStages)) {
            scheduleBackgroundFill(topicId, topicTitle, missing, seedUrl, locale)
            return@withLock Result.success(cached ?: DeepReadOutput())
        }
        if (!force && cached != null && cached.sectionsReady()) {
            val completed = cached.copy(
                generationPhase = DeepReadGenerationPhase.COMPLETE,
                generationComplete = true,
            )
            hotListRepository.saveDeepRead(topicId, topicTitle, completed, ttlDays = currentDeepReadTtlDays())
            persistCompletedArtifact(topicId, topicTitle, completed)
            return@withLock Result.success(completed)
        }

        val stagesToGenerate = when {
            missing.isNotEmpty() -> missing
            else -> DeepReadGenerationStage.entries
        }

        generateStages(
            topicId = topicId,
            topicTitle = topicTitle,
            stages = stagesToGenerate,
            seedUrl = seedUrl,
            force = force,
            propagateFailuresWithPartial = propagateFailuresWithPartial,
            runId = runId,
            events = events,
            locale = locale,
        )
    }

    suspend fun runSection(
        topicId: String,
        topicTitle: String,
        stage: DeepReadGenerationStage,
        seedUrl: String? = null,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> = topicMutex(topicId).withLock {
        markSectionRunning(topicId, topicTitle, seedUrl, stage)
        generateStages(
            topicId = topicId,
            topicTitle = topicTitle,
            stages = listOf(stage),
            seedUrl = seedUrl,
            markCollecting = false,
            planningPhase = DeepReadGenerationPhase.WRITING,
            propagateFailuresWithPartial = propagateFailuresWithPartial,
            runId = runId,
            events = events,
            locale = locale,
        )
    }

    private fun scheduleBackgroundFill(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
        locale: Locale,
    ) {
        val key = "$topicId:${stages.joinToString(",") { it.name }}"
        if (!backgroundRuns.add(key)) return
        appScope.launch {
            try {
                topicMutex(topicId).withLock {
                    val current = fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()
                    val stillMissing = stages.filter { current.statusOf(it) != DeepReadSectionStatus.READY }
                    // Explicit force = false: background fill should reuse the
                    // prefetch cache populated by the primary run instead of
                    // re-paying the 36s budget. This is the biggest measurable
                    // win from Phase E.
                    if (stillMissing.isNotEmpty()) {
                        generateStages(
                            topicId = topicId,
                            topicTitle = topicTitle,
                            stages = stillMissing,
                            seedUrl = seedUrl,
                            force = false,
                            locale = locale,
                        )
                    }
                }
            } finally {
                backgroundRuns.remove(key)
            }
        }
    }

    private suspend fun markSectionRunning(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        stage: DeepReadGenerationStage,
    ) {
        val current = fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()
        val next = current.withSectionRetryRunning(stage)
        if (next == current) return
        hotListRepository.saveDeepRead(
            topicId = topicId,
            title = topicTitle,
            output = next,
            ttlDays = currentDeepReadTtlDays(),
        )
    }

    private suspend fun generateStages(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
        force: Boolean = false,
        markCollecting: Boolean = true,
        planningPhase: DeepReadGenerationPhase = DeepReadGenerationPhase.PLANNING,
        propagateFailuresWithPartial: Boolean = false,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadOutput> {
        val context = createRunContext(
            topicId = topicId,
            topicTitle = topicTitle,
            seedUrl = seedUrl,
            force = force,
            markCollecting = markCollecting,
            planningPhase = planningPhase,
            runId = runId,
            events = events,
            locale = locale,
        ).getOrElse { error ->
            if (error is CancellationException) throw error
            return Result.failure(error)
        }

        return runCatching {
            context.writer.markRunning(stages)

            // OVERVIEW must settle before NARRATIVE/ANALYSIS so they can read
            // overview.summary and overview.key_entities from writer state when
            // building their prompts.
            if (DeepReadGenerationStage.OVERVIEW in stages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.OVERVIEW)
            }

            // NARRATIVE should settle before ANALYSIS. In practice the hidden
            // model is more reliable when analysis can read the narrative it is
            // meant to interpret, and it avoids two long generation/tool loops
            // competing at once on mobile networks.
            if (DeepReadGenerationStage.NARRATIVE in stages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.NARRATIVE)
            }
            if (DeepReadGenerationStage.ANALYSIS in stages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.ANALYSIS)
            }

            // EXTENDED_READING collates references and image_assets; needs the
            // prior stages settled.
            if (DeepReadGenerationStage.EXTENDED_READING in stages) {
                runStageSupervisorLoop(context, DeepReadGenerationStage.EXTENDED_READING)
            }

            val allTargetedReady = stages.all {
                context.writer.currentOutput().statusOf(it) == DeepReadSectionStatus.READY
            }
            val allSectionsReady = context.writer.currentOutput().sectionsReady()
            if (allTargetedReady && allSectionsReady) {
                val completed = finishIfPossible(context.writer, context.locale)
                persistCompletedArtifact(topicId, topicTitle, completed)
                return@runCatching completed
            }
            if (allTargetedReady) {
                context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                return@runCatching context.writer.currentOutput()
            }

            // Anything that didn't reach READY gets a clear FAILED so the UI
            // shows an error instead of a perpetual loading skeleton.
            val missing = stages.filter {
                context.writer.currentOutput().statusOf(it) != DeepReadSectionStatus.READY
            }
            markMissingFailed(
                context.writer,
                missing,
                appContext.getString(R.string.deep_read_generation_failed),
            )
            context.writer.markPhase(DeepReadGenerationPhase.IDLE)
            context.writer.currentOutput()
        }.fold(
            onSuccess = { output ->
                if (output.hasAnyReadySection() || output.isComplete()) Result.success(output) else Result.failure(
                    IllegalStateException(firstFailure(output) ?: appContext.getString(R.string.deep_read_generation_failed))
                )
            },
            onFailure = { error ->
                // kotlin.runCatching swallows CancellationException, which would
                // otherwise unwind structured concurrency correctly. Re-throw it
                // so user-initiated cancel (UI navigation away, parent scope
                // cancel) is not silently turned into a Result.failure here.
                if (error is CancellationException) throw error
                Log.e(TAG, "deep read hidden run failed", error)
                if (propagateFailuresWithPartial) {
                    context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                    return@fold Result.failure(error)
                }
                markMissingFailed(
                    writer = context.writer,
                    stages = stages,
                    message = error.message ?: error::class.simpleName.orEmpty(),
                )
                val output = context.writer.markPhase(DeepReadGenerationPhase.IDLE)
                if (output.hasAnyReadySection()) Result.success(output) else Result.failure(error)
            },
        )
    }

    private suspend fun createRunContext(
        topicId: String,
        topicTitle: String,
        seedUrl: String?,
        force: Boolean,
        markCollecting: Boolean,
        planningPhase: DeepReadGenerationPhase,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale,
    ): Result<DeepReadRunContext> {
        return try {
            val settings = settingsStore.settingsFlow.value
            val resolvedModel = resolveModel(settings)
                ?: return Result.failure(
                    IllegalStateException(appContext.getString(R.string.setting_model_page_follow_chat_model_unavailable))
                )
            if (ModelAbility.TOOL !in resolvedModel.abilities) {
                return Result.failure(IllegalStateException(appContext.getString(R.string.tools_warning)))
            }
            val model = resolvedModel.withBoardRequestOptions(settings)
            if (markCollecting) {
                hotListRepository.saveDeepRead(
                    topicId = topicId,
                    title = topicTitle,
                    output = (fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()).copy(
                        generationPhase = DeepReadGenerationPhase.COLLECTING,
                        generationComplete = false,
                    ),
                    ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
                )
            }
            val prefetchedSources = sourcePrefetcher.collect(
                topicId = topicId,
                topicTitle = topicTitle,
                seedUrl = seedUrl,
                force = force,
                locale = locale,
            )
            if (prefetchedSources.isEmpty()) {
                val message = appContext.getString(R.string.deep_read_generation_failed)
                Log.w(TAG, "deep read prefetch returned no sources for topic=$topicId")
                if (markCollecting) {
                    hotListRepository.saveDeepRead(
                        topicId = topicId,
                        title = topicTitle,
                        output = (fresh(topicId, topicTitle, seedUrl) ?: DeepReadOutput()).copy(
                            generationPhase = DeepReadGenerationPhase.IDLE,
                        ),
                        ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
                    )
                }
                return Result.failure(IllegalStateException(message))
            }
            Log.i(TAG, "deep read prefetch ready: ${prefetchedSources.size} sources for topic=$topicId")
            val evidencePack = researchHarness.buildEvidencePack(topicTitle, prefetchedSources)
            val evidenceRegistry = DeepReadEvidenceRegistry()
            seedEvidenceRegistry(evidenceRegistry, prefetchedSources, evidencePack, output = null)
            val writer = DeepReadSectionWriterTools(
                repository = hotListRepository,
                topicId = topicId,
                topicTitle = topicTitle,
                imageCandidates = prefetchedSources.flatMap { it.imageCandidates },
                allowTitleFallback = seedUrl.isNullOrBlank(),
                ttlDays = settings.agentRuntime.todayBoard.deepReadCacheTtlDays,
            )
            val hiddenSettings = DeepReadHiddenAssistantFactory.create(
                settings.toIsolatedSubAgentSettings(),
                locale = locale,
            )
            val playbook = playbookRepository.read()

            writer.markPhase(planningPhase)
            val articlePlan = generateArticlePlan(
                settings = hiddenSettings,
                model = model,
                topicTitle = topicTitle,
                evidencePack = evidencePack,
                playbookMarkdown = playbook.markdown,
                locale = locale,
                runId = runId,
                events = events,
            )
            Result.success(
                DeepReadRunContext(
                    settings = settings,
                    hiddenSettings = hiddenSettings,
                    model = model,
                    topicTitle = topicTitle,
                    seedUrl = seedUrl,
                    evidencePack = evidencePack,
                    evidenceRegistry = evidenceRegistry,
                    articlePlan = articlePlan,
                    playbookMarkdown = playbook.markdown,
                    writer = writer,
                    locale = locale,
                    runId = runId,
                    events = events,
                )
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun seedEvidenceRegistry(
        registry: DeepReadEvidenceRegistry,
        sources: List<DeepReadSource>,
        evidencePack: DeepReadEvidencePack,
        output: DeepReadOutput?,
    ) {
        sources.forEach { source ->
            registry.mark(source.url)
            source.evidenceText.takeIf { it.isNotBlank() }?.let { evidenceText ->
                registry.mark(source.url, evidenceText)
            }
        }
        evidencePack.cards.forEach { card ->
            card.evidenceExcerpt.takeIf { it.isNotBlank() }?.let { evidenceText ->
                registry.mark(card.source.url, evidenceText)
            }
        }
        output?.referencedEvidenceUrls().orEmpty().forEach(registry::mark)
    }

    private fun DeepReadOutput.referencedEvidenceUrls(): List<String> = buildList {
        references.forEach { add(it.url) }
        extendedReading.forEach { add(it.url) }
        heroImageUrl?.let(::add)
        imageAssets.forEach { add(it.url) }
        timeline.orEmpty().forEach { it.imageUrl?.let(::add) }
        corePoints.orEmpty().forEach { it.imageUrl?.let(::add) }
    }.filter { it.isHttpOrHttpsUrl() }

    private suspend fun runStageSupervisorLoop(
        context: DeepReadRunContext,
        stage: DeepReadGenerationStage,
        coverageReport: DeepReadCoverageReport? = null,
    ) {
        val singleStage = listOf(stage)
        val writer = context.writer
        val writerToolsForStage = writer.tools(stages = setOf(stage), locale = context.locale)
            .filter { it.name == stage.writerToolName() }
        val stageTimeoutMs = collectRunTimeoutFor(stage)
        val stageTools = toolSetFactory.forDeepRead(
            settings = context.settings,
            writerTools = writerToolsForStage,
            descriptionContext = DeepReadToolDescriptionContext(
                stageLabel = stage.localizedLabel(context.locale),
                writerToolName = stage.writerToolName(),
                stageTimeoutSeconds = stageTimeoutMs.toWholeSeconds(),
            ),
        )
            .map { it.withEvidenceRecording(context.evidenceRegistry) }
        val stageWriterToolNamesSet = writerToolsForStage.map { it.name }.toSet()
        val stageEvidence = context.evidencePack.cardsFor(
            stage = stage,
            plan = context.articlePlan,
            forceIncludeSourceIds = coverageReport?.missingRequiredSourceIds.orEmpty(),
        )
        val stageSources = stageEvidence.map { it.source }
        val scrapeWebAvailable = stageTools.any { it.name == "scrape_web" }
        var messages = listOf(
            UIMessage.user(
                buildPrompt(
                    topicTitle = context.topicTitle,
                    stages = singleStage,
                    existingOutput = writer.currentOutput(),
                    seedUrl = context.seedUrl,
                    scrapeWebAvailable = scrapeWebAvailable,
                    evidencePack = context.evidencePack,
                    articlePlan = context.articlePlan,
                    stageEvidence = stageEvidence,
                    targetStage = stage,
                    stageTimeoutMs = stageTimeoutMs,
                    playbookMarkdown = context.playbookMarkdown,
                    coverageReport = coverageReport,
                    locale = context.locale,
                )
            )
        )
        try {
            val initialRequiredWrites = writer.requiredWriteCount
            repeat(MAX_SUPERVISOR_PASSES) { pass ->
                val beforeWrites = writer.requiredWriteCount
                messages = withTimeout(stageTimeoutMs) {
                    collectRun(
                        settings = context.hiddenSettings,
                        model = context.model,
                        messages = messages,
                        tools = stageTools,
                        writerToolNames = stageWriterToolNamesSet,
                        statusLabel = if (context.locale.isChineseLocale()) {
                            "深度阅读 ${stage.localizedLabel(context.locale)}"
                        } else {
                            "Deep Read ${stage.localizedLabel(context.locale)}"
                        },
                        runId = context.runId,
                        events = context.events,
                    )
                }
                val stageReady = writer.currentOutput().statusOf(stage) == DeepReadSectionStatus.READY
                val supplementWritten = coverageReport == null ||
                    writer.requiredWriteCount > initialRequiredWrites
                if (stageReady && supplementWritten) {
                    return
                }
                if (writer.requiredWriteCount == beforeWrites) {
                    messages = messages + UIMessage.user(buildWriterReminder(singleStage, pass, context.locale))
                }
            }
            val needsFallback = writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY ||
                (coverageReport != null && writer.requiredWriteCount == initialRequiredWrites)
            if (needsFallback) {
                tryFallbackAfterStageFailure(
                    writer = writer,
                    stage = stage,
                    messages = messages,
                    sources = stageSources,
                    reason = "missing writer tool",
                    allowReadyRewrite = coverageReport != null,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "deep read stage ${stage.label} timed out", timeout)
            val recovered = tryFallbackAfterStageFailure(
                writer = writer,
                stage = stage,
                messages = messages,
                sources = stageSources,
                reason = "timeout",
                allowReadyRewrite = coverageReport != null,
            )
            if (!recovered && writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                writer.markFailed(stage, timeoutFailureMessage(stage, stageTimeoutMs))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (other: Throwable) {
            Log.e(TAG, "deep read stage ${stage.label} failed", other)
            val recovered = tryFallbackAfterStageFailure(
                writer = writer,
                stage = stage,
                messages = messages,
                sources = stageSources,
                reason = if (other.isDeepReadTimeoutLike()) "provider timeout" else "failure",
                allowReadyRewrite = coverageReport != null,
            )
            if (!recovered && writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                writer.markFailed(
                    stage,
                    stageFailureMessage(stage, other, collectRunTimeoutFor(stage)),
                )
            }
        }
    }

    private suspend fun collectRun(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        tools: List<app.amber.ai.core.Tool>,
        writerToolNames: Set<String>,
        statusLabel: String,
        runId: String? = null,
        events: AgentEventWriter? = null,
    ): List<UIMessage> {
        var latest = messages
        suspend fun runWith(stream: Boolean) {
            kernel.run(
                GenerationRunSession(
                    settings = settings.copy(streamOutput = stream),
                model = model,
                messages = messages,
                memories = emptyList(),
                tools = tools,
                maxSteps = MAX_GENERATION_STEPS,
                processingStatus = MutableStateFlow(statusLabel),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
                autoApprovedToolNames = writerToolNames,
                invocationContext = ToolInvocationContext.Normal,
                conversation = null,
                // Step 5: thread the run scope's identity and event writer the
                // same way SubAgentRunner does; the kernel's durable-path gate
                // decides whether anything is emitted.
                runId = runId,
                toolLifecycleEvents = events,
                // Step 6: v1 default codifies no new restriction; narrowing
                // arrives via sub-agent payloads.
                executionPolicy = app.amber.feature.runtime.ExecutionPolicy.permissive(),
                    inputTransformers = emptyList(),
                    outputTransformers = emptyList(),
                ),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) latest = chunk.messages
            }
        }
        try {
            runWith(stream = settings.streamOutput)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isDeepReadTimeoutLike()) throw error
            Log.w(TAG, "deep read stream failed; retrying through non-stream generator path", error)
            latest = messages
            runWith(stream = false)
        }
        return latest
    }

    private suspend fun generateArticlePlan(
        settings: Settings,
        model: Model,
        topicTitle: String,
        evidencePack: DeepReadEvidencePack,
        playbookMarkdown: String,
        runId: String? = null,
        events: AgentEventWriter? = null,
        locale: Locale,
    ): DeepReadArticlePlan {
        val fallback = researchHarness.fallbackPlan(topicTitle, evidencePack, locale)
        val messages = runCatching {
            collectRun(
                settings = settings.copy(streamOutput = false),
                model = model,
                messages = listOf(
                    UIMessage.user(
                        researchHarness.buildPlanningPrompt(
                            topicTitle = topicTitle,
                            pack = evidencePack,
                            playbookMarkdown = playbookMarkdown,
                            locale = locale,
                        )
                    )
                ),
                tools = emptyList(),
                writerToolNames = emptySet(),
                statusLabel = if (locale.isChineseLocale()) "深度阅读 结构规划" else "Deep Read structure planning",
                runId = runId,
                events = events,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "deep read planning fell back to local plan", error)
            emptyList()
        }
        val parsed = researchHarness.parsePlan(messages.latestAssistantText())
        return researchHarness.normalizePlan(
            parsed = parsed ?: fallback,
            topicTitle = topicTitle,
            pack = evidencePack,
            locale = locale,
        )
    }

    private suspend fun finishIfPossible(writer: DeepReadSectionWriterTools, locale: Locale): DeepReadOutput {
        val finish = writer.tools(locale = locale).first { it.name == "deep_read_finish" }
        finish.execute(kotlinx.serialization.json.buildJsonObject { })
        return writer.currentOutput()
    }

    private suspend fun persistCompletedArtifact(
        topicId: String,
        topicTitle: String,
        output: DeepReadOutput,
    ) {
        if (!output.isComplete()) return
        val repository = artifactRepository ?: return
        runCatching {
            repository.saveDeepRead(
                topicId = topicId,
                title = topicTitle,
                content = JsonInstant.encodeToString(DeepReadOutput.serializer(), output),
            )
        }.onFailure { error ->
            // Workspace sync must not turn an otherwise completed DeepRead
            // into a failed generation; the registry can be retried from the
            // cached completion on the next open/run.
            Log.w(TAG, "completed deep read workspace sync failed", error)
        }
    }

    private suspend fun markMissingFailed(
        writer: DeepReadSectionWriterTools,
        stages: List<DeepReadGenerationStage>,
        message: String,
    ): DeepReadOutput {
        var current = writer.currentOutput()
        stages.forEach { stage ->
            if (current.statusOf(stage) != DeepReadSectionStatus.READY) {
                current = writer.markFailed(stage, message)
            }
        }
        return current
    }

    private suspend fun tryFallbackAfterStageFailure(
        writer: DeepReadSectionWriterTools,
        stage: DeepReadGenerationStage,
        messages: List<UIMessage>,
        sources: List<DeepReadSource>,
        reason: String,
        allowReadyRewrite: Boolean,
    ): Boolean {
        if (writer.currentOutput().statusOf(stage) == DeepReadSectionStatus.READY) return true
        // writeFallbackSection is now links-only (spec A1): it merges real source links but
        // never synthesizes占位 body and never marks the section READY. So a failing stage
        // can no longer "recover" via fallback — we only preserve the genuine links, then
        // the caller (runStageSupervisorLoop timeout/other branches) marks the stage FAILED.
        // Returning false here is by design: markFailed on an already-failed stage is a no-op.
        return try {
            writer.writeFallbackSection(
                stage = stage,
                assistantText = messages.latestAssistantText(),
                sources = sources,
                allowReadyRewrite = allowReadyRewrite,
            )
            Log.i(TAG, "deep read stage ${stage.label} preserved source links after $reason")
            false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(TAG, "deep read stage ${stage.label} link preservation failed after $reason", error)
            false
        }
    }

    private fun buildPrompt(
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        existingOutput: DeepReadOutput,
        seedUrl: String?,
        scrapeWebAvailable: Boolean,
        evidencePack: DeepReadEvidencePack,
        articlePlan: DeepReadArticlePlan,
        stageEvidence: List<DeepReadEvidenceCard>,
        targetStage: DeepReadGenerationStage,
        stageTimeoutMs: Long,
        playbookMarkdown: String,
        coverageReport: DeepReadCoverageReport? = null,
        locale: Locale,
    ): String = buildString {
        val chinese = locale.isChineseLocale()
        appendLine(if (chinese) "今天日期：${LocalDate.now()}" else "Today: ${LocalDate.now()}")
        appendLine(if (chinese) "话题标题：$topicTitle" else "Topic title: $topicTitle")
        appendLine(
            if (chinese) {
                "目标段落：${stages.joinToString(" → ") { it.localizedLabel(locale) }}"
            } else {
                "Target sections: ${stages.joinToString(" -> ") { it.localizedLabel(locale) }}"
            }
        )
        appendLine()
        appendLine(if (chinese) "## Deep Read Playbook（本地规则，只读）" else "## Deep Read Playbook (local, read-only rules)")
        appendLine(playbookMarkdown.take(PLAYBOOK_PROMPT_LIMIT))
        seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            appendLine(if (chinese) "用户指定来源 URL：$url" else "User-specified source URL: $url")
            appendLine(
                if (chinese) {
                    "该 URL 已在预抓阶段尝试读取，优先使用预抓正文；如下面预抓正文为空再 search_web/scrape_web 补充。"
                } else {
                    "This URL was attempted during prefetch. Prefer its prefetched text; use search_web/scrape_web only when that text is empty."
                }
            )
        }
        appendLine()
        appendArticlePlan(articlePlan, locale)
        appendLine()
        coverageReport?.let { report ->
            appendLine(if (chinese) "## 本轮补漏目标" else "## Coverage gaps for this pass")
            appendLine(report.promptSummary(locale))
            appendLine(
                if (chinese) {
                    "只补上述缺项，不要重写整篇。已有段落可保留，只更新目标段落中缺失的事实、立场、影响或来源。"
                } else {
                    "Address only those gaps; do not rewrite the whole article. Keep existing sections and update only missing facts, positions, impacts, or sources in the target section."
                }
            )
            appendLine()
        }
        appendEvidenceCards(
            cards = stageEvidence,
            title = if (chinese) {
                "本段证据包（全局共 ${evidencePack.cards.size} 条，本轮只给最相关 ${stageEvidence.size} 条）"
            } else {
                "Evidence pack for this section (${evidencePack.cards.size} total; ${stageEvidence.size} most relevant in this pass)"
            },
            excerptLimit = targetStage.promptExcerptLimit(),
            locale = locale,
        )
        appendLine()
        appendArticleContext(existingOutput, locale)
        appendLine()
        appendDeadlineGuidance(targetStage, stageTimeoutMs, locale)
        appendLine()
        appendLine(if (chinese) "## 研究顺序" else "## Research order")
        if (chinese) {
            appendLine("1. 本地 harness 已经完成来源扩展、去重、分桶和结构规划。你只处理当前小目标。")
            appendLine("2. 仅在以下情况补充 search_web：")
            appendLine("   - 关键事实（发布时间、价格、官方表态、版本号等）在预抓来源中找不到或互相矛盾")
            appendLine("   - 用户指定 URL 的预抓正文为空，需要别的 query 命中该话题")
            appendLine("   - 你需要反面证据时（例如「辟谣」「不实」「未确认」）")
        } else {
            appendLine("1. The local harness has already expanded, deduplicated, bucketed, and planned the sources. Handle only the current target.")
            appendLine("2. Supplement with search_web only in these cases:")
            appendLine("   - Key facts (publication time, price, official statement, version, and so on) are missing or contradictory in the prefetched sources")
            appendLine("   - The prefetched body for the user-specified URL is empty and another query is needed to hit the topic")
            appendLine("   - You need counter-evidence, such as a denial, a false claim, or an unconfirmed claim")
        }
        if (scrapeWebAvailable) {
            appendLine(
                if (chinese) {
                    "3. 仅在确认某个 URL 比预抓正文更详细时再 scrape_web；不要把预抓已有正文重抓一次。"
                } else {
                    "3. Use scrape_web only after confirming that a URL has more detail than its prefetched text; do not scrape the same prefetched body again."
                }
            )
        } else {
            appendLine(
                if (chinese) {
                    "3. 当前未暴露 scrape_web；只能基于预抓正文 + 必要时的 search_web 摘要写入。"
                } else {
                    "3. scrape_web is not exposed in this pass; write only from prefetched text plus search_web snippets when necessary."
                }
            )
        }
        appendLine(
            if (chinese) {
                "4. 本轮只生成目标段落，不要改写或重写未列入目标的段落。"
            } else {
                "4. Generate only the target section in this pass; do not edit or rewrite sections outside the target."
            }
        )
        appendLine(if (chinese) "5. 完成研究后立即调用对应 writer tool：" else "5. After research, immediately call the corresponding writer tool:")
        stages.forEach { stage -> appendLine("   - ${stage.writerToolName()}: ${stage.localizedLabel(locale)}") }
        appendLine(
            if (chinese) {
                "6. 本轮只暴露目标段 writer tool；如果你输出自由文本但没调工具，系统会尝试把自由文本转换成基础稿。"
            } else {
                "6. Only the target-section writer tool is exposed in this pass; if you return free text without calling it, the system may convert that text into a basic draft."
            }
        )
        appendLine(
            if (chinese) {
                "7. 图片只能从 image_candidates 中选择；本轮如果没有视觉 writer，就在目标段 references/links 中保留可用来源。"
            } else {
                "7. Select images only from image_candidates; when no visual writer is available, keep usable sources in the target section's references/links."
            }
        )
        appendLine(
            if (chinese) {
                "8. 全部 writer 完成后，直接调用 deep_read_finish。"
            } else {
                "8. After all writers are complete, call deep_read_finish directly."
            }
        )
        appendLine()
        appendLine(if (chinese) "## 段落要求" else "## Section requirements")
        if (DeepReadGenerationStage.OVERVIEW in stages) {
            appendLine(
                if (chinese) {
                    "- 概览：约 120-250 字中文杂志导语，说明事件是什么、为什么值得读、哪些事实已核查；完整句子优先，略超可以接受。"
                } else {
                    "- Overview: a 120-250-word magazine-style lead explaining what happened, why it matters, and which facts are verified; prefer complete sentences."
                }
            )
        }
        if (DeepReadGenerationStage.NARRATIVE in stages) {
            appendLine(
                if (chinese) {
                    "- 时间轴叙事：事件型写 timeline；观点/产品/人物型可写 core_points，但要有故事性和演化脉络。"
                } else {
                    "- Narrative: use timeline for events; opinion/product/person topics may use core_points, but preserve a clear story and evolution."
                }
            )
        }
        if (DeepReadGenerationStage.ANALYSIS in stages) {
            appendLine(
                if (chinese) {
                    "- 深度分析：围绕核心分歧、各方立场、影响分析；这一段需要充分 reasoning，但不要输出 reasoning 给 UI。"
                } else {
                    "- Analysis: cover the core dispute, stakeholder positions, and implications; reason thoroughly but do not expose reasoning to the UI."
                }
            )
        }
        if (DeepReadGenerationStage.EXTENDED_READING in stages) {
            appendLine(if (chinese) "- 扩展阅读：只放真实来源链接和真实图片资产。" else "- Extended reading: include only real source links and real image assets.")
        }
        appendLine(
            if (chinese) {
                "- 视觉：头图必须来自候选池且 confidence=hero；inline 候选只能作为正文图。不得提交任意 URL、站点 logo、favicon、媒体图标或头像。"
            } else {
                "- Visuals: the hero image must come from the candidate pool with confidence=hero; inline candidates are for body images only. Never submit arbitrary URLs, site logos, favicons, media icons, or avatars."
            }
        )
        appendLine(
            if (chinese) {
                "- 图解：只提交 3-6 个短节点的 diagram spec，节点 label 控制在约 30 字内；流程/因果可保留少量关键跨节点关系，但避免网状交叉。禁止 raw SVG/HTML/JS/外链资源。图解不参与段落完成状态，不需要就隐藏。"
            } else {
                "- Diagrams: submit only a 3-6-node diagram spec with labels around 30 characters; flow/causal diagrams may keep a few important cross-node relations, but avoid tangled networks. No raw SVG/HTML/JS or external resources. Diagrams do not determine section completion and may be omitted."
            }
        )
        appendLine()
        appendLine(
            if (chinese) {
                "- 用户可见的 summary、timeline/core_points、analysis、extended_reading、references 和图片说明一律使用中文；URL、专有名词及必要原文保持不变。"
            } else {
                "- Write all user-visible summary, timeline/core_points, analysis, extended_reading, references, and image captions in English; keep URLs, proper nouns, and necessary original names unchanged."
            }
        )
        appendLine(if (chinese) "正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。" else "The UI does not consume free-form text. Do not return the full JSON or a long Markdown article as the final answer.")
    }

    private fun StringBuilder.appendDeadlineGuidance(
        stage: DeepReadGenerationStage,
        stageTimeoutMs: Long,
        locale: Locale,
    ) {
        val chinese = locale.isChineseLocale()
        val stageSeconds = stageTimeoutMs.toWholeSeconds()
        appendLine(if (chinese) "## 时间预算（硬约束）" else "## Time budget (hard constraint)")
        appendLine(if (chinese) "- 本段运行预算约 ${stageSeconds} 秒。" else "- This section has a runtime budget of about ${stageSeconds} seconds.")
        appendLine(
            if (chinese) {
                "- 你必须把第一优先级放在调用 ${stage.writerToolName()}；不要先输出长文、完整 JSON 或 Markdown 草稿。"
            } else {
                "- Prioritize calling ${stage.writerToolName()} above all else; do not output a long article, complete JSON, or Markdown draft first."
            }
        )
        appendLine(
            if (chinese) {
                "- 预抓证据包是主材料。除非关键事实缺失或互相矛盾，不要连续 search_web / scrape_web。"
            } else {
                "- The prefetched evidence pack is the primary material. Do not chain search_web / scrape_web calls unless key facts are missing or contradictory."
            }
        )
        appendLine(
            if (chinese) {
                "- 如果证据不够完整，先基于现有证据写保守版本；不要为了补全而耗尽本段预算。"
            } else {
                "- If the evidence is incomplete, write a cautious version from what is available; do not exhaust this section's budget trying to fill every gap."
            }
        )
        if (stage == DeepReadGenerationStage.EXTENDED_READING) {
            appendLine(
                if (chinese) {
                    "- 扩展阅读不是长文写作：从本段证据包挑选 4-8 条真实来源链接，必要时带 image_assets，然后立即调用 writer tool。"
                } else {
                    "- Extended reading is not long-form writing: choose 4-8 real source links from this section's evidence pack, add image_assets when needed, then call the writer tool immediately."
                }
            )
        }
    }

    private fun StringBuilder.appendArticlePlan(plan: DeepReadArticlePlan, locale: Locale) {
        appendLine(if (locale.isChineseLocale()) "## Article Plan（本地 harness 规划，只读）" else "## Article Plan (local harness plan, read-only)")
        appendLine("- angle: ${plan.overviewAngle}")
        if (plan.narrativeSlots.isNotEmpty()) {
            appendLine("- narrative_slots: ${plan.narrativeSlots.joinToString(" / ")}")
        }
        if (plan.analysisQuestions.isNotEmpty()) {
            appendLine("- analysis_questions:")
            plan.analysisQuestions.forEach { question -> appendLine("  - $question") }
        }
        if (plan.stakeholders.isNotEmpty()) {
            appendLine("- stakeholders: ${plan.stakeholders.joinToString(" / ")}")
        }
        if (plan.riskOrUncertainty.isNotEmpty()) {
            appendLine("- risk_or_uncertainty:")
            plan.riskOrUncertainty.forEach { risk -> appendLine("  - $risk") }
        }
        appendLine("- required_source_ids: ${plan.requiredSourceIds.joinToString(", ")}")
    }

    private fun StringBuilder.appendEvidenceCards(
        cards: List<DeepReadEvidenceCard>,
        title: String = "Evidence Pack",
        excerptLimit: Int = PROMPT_SOURCE_EXCERPT_LIMIT,
        locale: Locale,
    ) {
        appendLine("## $title")
        if (cards.isEmpty()) {
            appendLine(
                if (locale.isChineseLocale()) {
                    "- 本段没有可用证据（理论上不会到这里）。"
                } else {
                    "- No usable evidence is available for this section (this should not normally occur)."
                }
            )
            return
        }
        cards.forEach { card ->
            val source = card.source
            appendLine("### ${card.sourceId}. ${card.title}")
            if (source.url.isNotBlank()) appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            appendLine("- tags: ${card.topicTags.joinToString { it.label }}")
            appendLine("- credibility: ${card.credibilityHint}; freshness: ${card.freshnessHint}")
            card.claimSummary.takeIf { it.isNotBlank() }?.let { appendLine("- claim_summary: $it") }
            source.publishedAt?.takeIf { it.isNotBlank() }?.let { appendLine("- published_at: $it") }
            val candidates = source.imageCandidates
                .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
                .take(4)
            if (candidates.isNotEmpty()) {
                appendLine("- image_candidates:")
                candidates.forEach { candidate ->
                    val risks = candidate.riskFlags.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "-"
                    appendLine(
                        "  - ${candidate.confidence} score=${candidate.score} kind=${candidate.candidateKind} " +
                            "risk=$risks url=${candidate.imageUrl} alt=${candidate.alt.orEmpty().take(80)}"
                    )
                }
            }
            val excerpt = card.evidenceExcerpt.take(excerptLimit).replace("\n", " ").trim()
            if (excerpt.isNotBlank()) appendLine("- evidence_excerpt: $excerpt")
            appendLine()
        }
    }

    private fun buildWriterReminder(
        stages: List<DeepReadGenerationStage>,
        pass: Int,
        locale: Locale,
    ): String = buildString {
        if (locale.isChineseLocale()) {
            appendLine("Supervisor reminder #${pass + 1}: 上一轮没有任何 deep_read_write_* 写入。")
            appendLine("时间提醒：现在请直接调用 writer tool。")
            appendLine("UI 不会消费你的自由文本。请立刻继续研究缺口，然后调用以下 writer tool 中至少一个：")
            stages.forEach { stage -> appendLine("- ${stage.writerToolName()} for ${stage.localizedLabel(locale)}") }
            appendLine("如果来源不足，只把当前段落写为 FAILED 的决定留给系统；不要用自由文本交差。")
        } else {
            appendLine("Supervisor reminder #${pass + 1}: the previous pass made no deep_read_write_* call.")
            appendLine("Time reminder: call the writer tool directly now.")
            appendLine("The UI does not consume free-form text. Continue researching the gap, then call at least one of these writer tools:")
            stages.forEach { stage -> appendLine("- ${stage.writerToolName()} for ${stage.localizedLabel(locale)}") }
            appendLine("If evidence is insufficient, let the system mark this section FAILED; do not substitute free-form text.")
        }
    }

    private fun Tool.withEvidenceRecording(registry: DeepReadEvidenceRegistry): Tool {
        if (name !in EVIDENCE_RECORDING_TOOL_NAMES) return this
        val original = this
        return copy(
            execute = { input ->
                val parts = original.execute(input)
                registry.markToolResult(original.name, input, parts)
                parts
            }
        )
    }

    private fun StringBuilder.appendArticleContext(output: DeepReadOutput, locale: Locale) {
        val current = output.withInferredSectionStates()
        appendLine(if (locale.isChineseLocale()) "## 当前稿件正文（补段时必须覆盖）" else "## Current article content (must be covered when supplementing a section)")
        appendLine(
            "section_states: " + DeepReadGenerationStage.entries.joinToString(", ") {
                "${it.name.lowercase()}=${current.statusOf(it).name.lowercase()}"
            }
        )
        if (!current.hasAnyReadySection() && current.summary.isBlank()) {
            appendLine(if (locale.isChineseLocale()) "- 暂无已写入段落。" else "- No sections have been written yet.")
            return
        }
        if (current.summary.isNotBlank()) {
            appendLine("overview.summary: ${current.summary}")
        }
        if (current.keyEntities.isNotEmpty()) {
            appendLine("overview.key_entities: ${current.keyEntities.take(12).joinToString(" / ")}")
        }
        current.heroImageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            appendLine("visual.hero: $imageUrl")
            appendLine("visual.hero_confidence: ${current.heroImageConfidence.orEmpty()}")
            current.heroCaption?.takeIf { it.isNotBlank() }?.let { caption ->
                appendLine("visual.hero_caption: $caption")
            }
            current.visualDiagnostics?.heroSelection?.let { selection ->
                appendLine("visual.hero_reason: ${selection.reason.take(320)}")
                appendLine("visual.hero_risks: ${selection.riskFlags.take(8).joinToString(" / ")}")
            }
        }
        current.imageAssets.take(8).takeIf { it.isNotEmpty() }?.let { assets ->
            appendLine("visual.inline_assets:")
            assets.forEach { asset ->
                appendLine(
                    "- ${asset.confidence.orEmpty()} score=${asset.score ?: 0} " +
                        "${asset.url} ${asset.caption.orEmpty()}"
                )
            }
        }
        current.timeline.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { timeline ->
            appendLine("narrative.timeline:")
            timeline.forEachIndexed { index, event ->
                appendLine("- ${index + 1}. ${event.date}: ${event.event}")
                if (!event.imageUrl.isNullOrBlank() || !event.imageCaption.isNullOrBlank()) {
                    appendLine(
                        "  image: ${event.imageUrl.orEmpty()} " +
                            event.imageCaption.orEmpty()
                    )
                }
            }
        }
        current.corePoints.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { points ->
            appendLine("narrative.core_points:")
            points.forEachIndexed { index, point ->
                appendLine("- ${index + 1}. ${point.point} ${point.supporting.orEmpty()}")
                if (!point.imageUrl.isNullOrBlank() || !point.imageCaption.isNullOrBlank()) {
                    appendLine(
                        "  image: ${point.imageUrl.orEmpty()} " +
                            point.imageCaption.orEmpty()
                    )
                }
            }
        }
        if (
            !current.analysis.coreDispute.isNullOrBlank() ||
            current.analysis.perspectives.isNotEmpty() ||
            !current.analysis.implications.isNullOrBlank() ||
            current.analysis.quotes.isNotEmpty()
        ) {
            appendLine("analysis:")
            current.analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
                appendLine("- core_dispute: $it")
            }
            current.analysis.perspectives.take(8).forEach { perspective ->
                appendLine("- perspective(${perspective.holder.orEmpty()}): ${perspective.viewpoint}")
            }
            current.analysis.implications?.takeIf { it.isNotBlank() }?.let {
                appendLine("- implications: $it")
            }
            current.analysis.quotes.take(6).forEach { quote ->
                appendLine("- quote(${quote.attribution.orEmpty()}): ${quote.text}")
            }
        }
        current.diagram?.takeIf { it.nodes.size >= 2 }?.let { diagram ->
            appendLine("diagram:")
            appendLine("- type: ${diagram.type}")
            appendLine("- title: ${diagram.title}")
            diagram.reason?.takeIf { it.isNotBlank() }?.let { appendLine("- reason: $it") }
            appendLine("- nodes:")
            diagram.nodes.take(8).forEach { node ->
                val group = node.group?.takeIf { it.isNotBlank() }?.let { " group=$it" }.orEmpty()
                appendLine(
                    "  - ${node.id}$group: ${node.label} " +
                        node.note.orEmpty()
                )
            }
            if (diagram.edges.isNotEmpty()) {
                appendLine("- edges:")
                diagram.edges.take(12).forEach { edge ->
                    appendLine("  - ${edge.from} -> ${edge.to}: ${edge.label.orEmpty()}")
                }
            }
            diagram.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                appendLine("- caption: $caption")
            }
        }
        current.extendedReading.take(10).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("extended_reading:")
            links.forEach { link ->
                appendLine("- ${link.title} | ${link.source.orEmpty()} | ${link.url}")
            }
        }
        current.references.take(12).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("references:")
            links.forEach { link ->
                appendLine("- ${link.title} | ${link.source.orEmpty()} | ${link.url}")
            }
        }
    }

    private fun currentDeepReadTtlDays(): Int =
        settingsStore.settingsFlow.value.agentRuntime.todayBoard.deepReadCacheTtlDays

    private fun resolveModel(settings: Settings): Model? {
        val boardModelId = settings.agentRuntime.todayBoard.boardModelId
        val specific = boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
        return specific ?: settings.resolveTaskChatModel(settings.chatModelId)
    }

    private fun Model.withBoardRequestOptions(settings: Settings): Model = copy(
        customHeaders = boardRequestHeaders(settings.providers),
        customBodies = boardRequestBodies(settings.providers),
        tools = emptySet(),
    )

    private suspend fun fresh(topicId: String, topicTitle: String, seedUrl: String?): DeepReadOutput? {
        val output = if (seedUrl.isNullOrBlank()) {
            hotListRepository.materializeFreshDeepRead(topicId, topicTitle)
        } else {
            hotListRepository.getFreshDeepRead(topicId)
        }
        return output?.withInferredSectionStates()
    }

    private fun topicMutex(topicId: String): Mutex = mutexes.getOrPut(topicId) { Mutex() }

    private fun missingStages(output: DeepReadOutput): List<DeepReadGenerationStage> =
        DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }

    private fun firstFailure(output: DeepReadOutput): String? =
        DeepReadGenerationStage.entries.firstNotNullOfOrNull { output.errorOf(it) }

    private fun collectRunTimeoutFor(stage: DeepReadGenerationStage): Long = when (stage) {
        DeepReadGenerationStage.OVERVIEW -> 90_000L
        DeepReadGenerationStage.NARRATIVE -> 110_000L
        DeepReadGenerationStage.ANALYSIS -> 150_000L
        DeepReadGenerationStage.EXTENDED_READING -> 90_000L
    }

    private fun Long.toWholeSeconds(): Long = (this / 1_000L).coerceAtLeast(1L)

    private fun timeoutFailureMessage(stage: DeepReadGenerationStage, timeoutMs: Long): String =
        "${appContext.getString(R.string.notification_live_status_timed_out)}: " +
            "${stageLabel(stage)} (${timeoutMs.toWholeSeconds()}s)"

    private fun stageFailureMessage(
        stage: DeepReadGenerationStage,
        error: Throwable,
        timeoutMs: Long,
    ): String =
        if (error.isDeepReadTimeoutLike()) {
            timeoutFailureMessage(stage, timeoutMs)
        } else {
            "${stageFailureLabel(stage)}: ${error.message ?: error::class.simpleName.orEmpty()}"
        }

    private fun stageLabel(stage: DeepReadGenerationStage): String = appContext.getString(
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> R.string.deep_read_overview
            DeepReadGenerationStage.NARRATIVE -> R.string.deep_read_narrative
            DeepReadGenerationStage.ANALYSIS -> R.string.deep_read_analysis
            DeepReadGenerationStage.EXTENDED_READING -> R.string.deep_read_reading
        }
    )

    private fun stageFailureLabel(stage: DeepReadGenerationStage): String = appContext.getString(
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> R.string.deep_read_overview_failed
            DeepReadGenerationStage.NARRATIVE -> R.string.deep_read_narrative_failed
            DeepReadGenerationStage.ANALYSIS -> R.string.deep_read_analysis_failed
            DeepReadGenerationStage.EXTENDED_READING -> R.string.deep_read_reading_failed
        }
    )

    private fun Throwable.isDeepReadTimeoutLike(): Boolean {
        if (this is TimeoutCancellationException) return true
        val haystack = listOfNotNull(
            message,
            localizedMessage,
            this::class.simpleName,
            this::class.qualifiedName,
        ).joinToString(" ")
        return DEEP_READ_TIMEOUT_MARKERS.any { marker -> haystack.contains(marker, ignoreCase = true) }
    }

    private fun previewTopicId(seedUrl: String): String =
        PREVIEW_TOPIC_PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(seedUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun String.isHttpOrHttpsUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    companion object {
        private const val TAG = "DeepReadAgentRunManager"
        private const val PREVIEW_TOPIC_PREFIX = "template-demo-"
        private const val MAX_GENERATION_STEPS = 32
        private const val MAX_SUPERVISOR_PASSES = 2

        // Prompt-side caps on how many pre-fetched sources we surface and how much
        // of each source body we inline. Anything beyond this stays in the
        // prefetcher cache and can still be reached if the model needs it via
        // search_web on the same URL.
        private const val PROMPT_SOURCE_LIMIT = 12
        private const val PROMPT_SOURCE_EXCERPT_LIMIT = 2_000
        private const val PLAYBOOK_PROMPT_LIMIT = 12_000
        private val EVIDENCE_RECORDING_TOOL_NAMES = setOf("search_web", "scrape_web")
        private val DEEP_READ_TIMEOUT_MARKERS = listOf(
            "timed out",
            "timeout",
            "SocketTimeout",
            "deadline exceeded",
            "Read timed out",
        )
    }
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

private fun DeepReadGenerationStage.localizedLabel(locale: Locale): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> if (locale.isChineseLocale()) "概览" else "Overview"
    DeepReadGenerationStage.NARRATIVE -> if (locale.isChineseLocale()) "时间轴叙事" else "Narrative"
    DeepReadGenerationStage.ANALYSIS -> if (locale.isChineseLocale()) "深度分析" else "Analysis"
    DeepReadGenerationStage.EXTENDED_READING -> if (locale.isChineseLocale()) "扩展阅读" else "Extended reading"
}

private fun DeepReadGenerationStage.writerToolName(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "deep_read_write_overview"
    DeepReadGenerationStage.NARRATIVE -> "deep_read_write_narrative"
    DeepReadGenerationStage.ANALYSIS -> "deep_read_write_analysis"
    DeepReadGenerationStage.EXTENDED_READING -> "deep_read_write_extended_reading"
}

internal fun shouldDeferDeepReadMissingStages(
    force: Boolean,
    cached: DeepReadOutput?,
    missing: List<DeepReadGenerationStage>,
    deferMissingStages: Boolean,
): Boolean =
    deferMissingStages && !force && cached?.hasAnyReadySection() == true && missing.isNotEmpty()

internal fun DeepReadOutput.withSectionRetryRunning(stage: DeepReadGenerationStage): DeepReadOutput {
    if (statusOf(stage) == DeepReadSectionStatus.READY) return this
    return copy(
        generationPhase = DeepReadGenerationPhase.WRITING,
        generationComplete = false,
    ).withSectionStatus(stage, DeepReadSectionStatus.RUNNING)
}


private fun DeepReadGenerationStage.promptExcerptLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 1_000
    DeepReadGenerationStage.NARRATIVE -> 1_400
    DeepReadGenerationStage.ANALYSIS -> 1_400
    DeepReadGenerationStage.EXTENDED_READING -> 700
}

private fun List<UIMessage>.latestAssistantText(): String =
    asReversed()
        .firstOrNull { it.role == app.amber.ai.core.MessageRole.ASSISTANT }
        ?.toText()
        .orEmpty()
