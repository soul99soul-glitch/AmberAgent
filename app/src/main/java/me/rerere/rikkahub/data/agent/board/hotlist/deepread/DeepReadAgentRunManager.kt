package me.rerere.rikkahub.data.agent.board.hotlist.deepread

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
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.board.boardRequestBodies
import me.rerere.rikkahub.data.agent.board.boardRequestHeaders
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.runtime.ToolInvocationContext
import me.rerere.rikkahub.data.agent.subagent.toIsolatedSubAgentSettings
import me.rerere.rikkahub.data.agent.tools.AgentToolSetFactory
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class DeepReadAgentRunManager(
    private val settingsStore: SettingsAggregator,
    private val generationHandler: GenerationHandler,
    private val hotListRepository: HotListRepository,
    private val toolSetFactory: AgentToolSetFactory,
    private val sourcePrefetcher: DeepReadSourcePrefetcher,
    private val playbookRepository: DeepReadPlaybookRepository,
    private val appScope: AppScope,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundRuns = ConcurrentHashMap.newKeySet<String>()

    suspend fun runPreview(
        topicTitle: String,
        seedUrl: String,
    ): Result<DeepReadOutput> {
        val normalizedSeedUrl = seedUrl.takeIf { it.isHttpOrHttpsUrl() }
            ?: return Result.failure(IllegalArgumentException("新闻 Demo 只支持 http/https URL"))
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
    ): Result<DeepReadOutput> = topicMutex(topicId).withLock {
        if (force) hotListRepository.clearDeepRead(topicId)

        val cached = if (force) null else fresh(topicId, topicTitle, seedUrl)
        if (cached?.isComplete() == true) {
            return@withLock Result.success(cached)
        }

        val missing = missingStages(cached ?: DeepReadOutput())
        if (!force && cached?.hasAnyReadySection() == true && missing.isNotEmpty()) {
            scheduleBackgroundFill(topicId, topicTitle, missing, seedUrl)
            return@withLock Result.success(cached)
        }

        val stagesToGenerate = when {
            missing.isNotEmpty() -> missing
            cached?.sectionsReady() == true -> emptyList()
            else -> DeepReadGenerationStage.entries
        }

        generateStages(topicId, topicTitle, stagesToGenerate, seedUrl, force)
    }

    suspend fun runSection(
        topicId: String,
        topicTitle: String,
        stage: DeepReadGenerationStage,
        seedUrl: String? = null,
    ): Result<DeepReadOutput> = topicMutex(topicId).withLock {
        generateStages(topicId, topicTitle, listOf(stage), seedUrl)
    }

    private fun scheduleBackgroundFill(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
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
                        generateStages(topicId, topicTitle, stillMissing, seedUrl, force = false)
                    }
                }
            } finally {
                backgroundRuns.remove(key)
            }
        }
    }

    private suspend fun generateStages(
        topicId: String,
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        seedUrl: String?,
        force: Boolean = false,
    ): Result<DeepReadOutput> {
        val settings = settingsStore.settingsFlow.value
        val resolvedModel = resolveModel(settings)
            ?: return Result.failure(IllegalStateException("请先配置今日看板模型或主聊天模型"))
        if (ModelAbility.TOOL !in resolvedModel.abilities) {
            return Result.failure(IllegalStateException("深度阅读需要配置支持工具调用的模型"))
        }
        val model = resolvedModel.withBoardRequestOptions(settings)
        val prefetchedSources = sourcePrefetcher.collect(
            topicId = topicId,
            topicTitle = topicTitle,
            seedUrl = seedUrl,
            force = force,
        )
        if (prefetchedSources.isEmpty()) {
            val message = "没有抓到足够的来源，无法生成深度阅读。请检查搜索源或稍后重试。"
            Log.w(TAG, "deep read prefetch returned no sources for topic=$topicId")
            return Result.failure(IllegalStateException(message))
        }
        Log.i(TAG, "deep read prefetch ready: ${prefetchedSources.size} sources for topic=$topicId")
        val evidenceRegistry = DeepReadEvidenceRegistry()
        prefetchedSources.forEach { source ->
            evidenceRegistry.mark(source.url)
            source.evidenceText.takeIf { it.isNotBlank() }?.let { evidenceText ->
                evidenceRegistry.mark(source.url, evidenceText)
            }
        }
        val writer = DeepReadSectionWriterTools(
            repository = hotListRepository,
            topicId = topicId,
            topicTitle = topicTitle,
            imageCandidates = prefetchedSources.flatMap { it.imageCandidates },
            isEvidenceUrlAllowed = evidenceRegistry::isAllowed,
            evidenceContains = evidenceRegistry::containsEvidence,
            allowTitleFallback = seedUrl.isNullOrBlank(),
        )
        val stageWriterTools = writer.tools(stages = stages.toSet())
        val stageTools = toolSetFactory.forDeepRead(settings, stageWriterTools)
            .map { it.withEvidenceRecording(evidenceRegistry) }
        val verificationWriterTools = writer.tools()
        val verificationTools = toolSetFactory.forDeepRead(settings, verificationWriterTools)
            .map { it.withEvidenceRecording(evidenceRegistry) }
        val scrapeWebAvailable = stageTools.any { it.name == "scrape_web" }
        val hiddenSettings = settings.toIsolatedSubAgentSettings()
        val assistant = DeepReadHiddenAssistantFactory.create(settings)
        val playbook = playbookRepository.read()

        return runCatching {
            writer.markRunning(stages)
            val stageWriterToolNamesSet = stageWriterTools.map { it.name }.toSet()
            val verificationWriterToolNamesSet = verificationWriterTools.map { it.name }.toSet()

            // Each stage runs its own bounded supervisor loop. A timeout or
            // local generation error marks only that section FAILED so the UI
            // can keep completed sections and offer section-level retry.
            suspend fun runStageSupervisorLoop(stage: DeepReadGenerationStage) {
                val singleStage = listOf(stage)
                var messages = listOf(
                    UIMessage.user(
                        buildPrompt(
                            topicTitle = topicTitle,
                            stages = singleStage,
                            existingOutput = writer.currentOutput(),
                            seedUrl = seedUrl,
                            scrapeWebAvailable = scrapeWebAvailable,
                            prefetchedSources = prefetchedSources,
                            targetStage = stage,
                            playbookMarkdown = playbook.markdown,
                        )
                    )
                )
                try {
                    repeat(MAX_SUPERVISOR_PASSES) { pass ->
                        val beforeWrites = writer.requiredWriteCount
                        messages = withTimeout(collectRunTimeoutFor(stage)) {
                            collectRun(
                                settings = hiddenSettings,
                                model = model,
                                messages = messages,
                                assistant = assistant,
                                tools = stageTools,
                                writerToolNames = stageWriterToolNamesSet,
                                statusLabel = "深度阅读 ${stage.label}",
                            )
                        }
                        if (writer.currentOutput().statusOf(stage) == DeepReadSectionStatus.READY) {
                            return
                        }
                        if (writer.requiredWriteCount == beforeWrites) {
                            messages = messages + UIMessage.user(buildWriterReminder(singleStage, pass))
                        }
                    }
                    if (writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                        val fallback = writer.writeFallbackSection(
                            stage = stage,
                            assistantText = messages.latestAssistantText(),
                            sources = prefetchedSources,
                        )
                        if (fallback.statusOf(stage) == DeepReadSectionStatus.READY) {
                            Log.w(TAG, "deep read stage ${stage.label} auto-filled after missing writer tool")
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    Log.w(TAG, "deep read stage ${stage.label} timed out", timeout)
                    if (writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                        writer.markFailed(stage, "${stage.label}超时未完成。")
                    }
                } catch (cancel: CancellationException) {
                    // External cancellation (e.g., user navigated away or parent
                    // job cancelled) must propagate so the whole DeepRead run
                    // stops cleanly.
                    throw cancel
                } catch (other: Throwable) {
                    // Local failure: mark this section FAILED and continue so
                    // already completed sections stay available to the reader.
                    Log.e(TAG, "deep read stage ${stage.label} failed", other)
                    if (writer.currentOutput().statusOf(stage) != DeepReadSectionStatus.READY) {
                        writer.markFailed(
                            stage,
                            "${stage.label}生成失败：${other.message ?: other::class.simpleName.orEmpty()}",
                        )
                    }
                }
            }

            // Global verification pass; only runs after all targeted stages
            // reach READY. Unlike section writes, this gets the full writer
            // surface so the model can correct an earlier cached section if
            // verification finds a problem.
            suspend fun runVerificationPass(
                pass: Int,
                previousFailure: String?,
                previousPassMissingToolCall: Boolean,
            ) {
                val current = writer.currentOutput()
                val verifyMessages = listOf(
                    UIMessage.user(
                        buildVerificationReminder(
                            topicTitle = topicTitle,
                            currentOutput = current,
                            prefetchedSources = prefetchedSources,
                            evidenceRegistry = evidenceRegistry,
                            pass = pass,
                            previousFailure = previousFailure,
                            previousPassMissingToolCall = previousPassMissingToolCall,
                        )
                    ),
                )
                try {
                    withTimeout(VERIFICATION_COLLECT_RUN_TIMEOUT_MS) {
                        collectRun(
                            settings = hiddenSettings,
                            model = model,
                            messages = verifyMessages,
                            assistant = assistant,
                            tools = verificationTools,
                            writerToolNames = verificationWriterToolNamesSet,
                            statusLabel = "深度阅读 验真",
                        )
                    }
                } catch (timeout: TimeoutCancellationException) {
                    Log.w(TAG, "deep read verification timed out", timeout)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (other: Throwable) {
                    Log.w(TAG, "deep read verification failed", other)
                }
            }

            suspend fun runVerificationSupervisorLoop() {
                writer.markVerificationRunning()
                var previousPassMissingToolCall = false
                repeat(MAX_VERIFICATION_PASSES) { passIndex ->
                    val beforeAttempts = writer.verificationAttemptCount
                    runVerificationPass(
                        pass = passIndex + 1,
                        previousFailure = writer.lastVerificationFailureReason,
                        previousPassMissingToolCall = previousPassMissingToolCall,
                    )
                    if (writer.hasFreshVerification) return
                    previousPassMissingToolCall = writer.verificationAttemptCount == beforeAttempts
                }
            }

            // OVERVIEW must settle before NARRATIVE/ANALYSIS so they can read
            // overview.summary and overview.key_entities from writer state when
            // building their prompts.
            if (DeepReadGenerationStage.OVERVIEW in stages) {
                runStageSupervisorLoop(DeepReadGenerationStage.OVERVIEW)
            }

            // NARRATIVE should settle before ANALYSIS. In practice the hidden
            // model is more reliable when analysis can read the narrative it is
            // meant to interpret, and it avoids two long generation/tool loops
            // competing at once on mobile networks.
            if (DeepReadGenerationStage.NARRATIVE in stages) {
                runStageSupervisorLoop(DeepReadGenerationStage.NARRATIVE)
            }
            if (DeepReadGenerationStage.ANALYSIS in stages) {
                runStageSupervisorLoop(DeepReadGenerationStage.ANALYSIS)
            }

            // EXTENDED_READING collates references and image_assets; needs the
            // prior stages settled.
            if (DeepReadGenerationStage.EXTENDED_READING in stages) {
                runStageSupervisorLoop(DeepReadGenerationStage.EXTENDED_READING)
            }

            // Verification + finish only when the full article is ready. A
            // single-section retry may make its target READY while other
            // sections are still missing; that must not stamp a global
            // verification state for the whole draft.
            val allTargetedReady = stages.all {
                writer.currentOutput().statusOf(it) == DeepReadSectionStatus.READY
            }
            val allSectionsReady = writer.currentOutput().sectionsReady()
            if (allTargetedReady && allSectionsReady && !writer.hasFreshVerification) {
                runVerificationSupervisorLoop()
            }
            if (allTargetedReady && allSectionsReady) {
                if (!writer.hasFreshVerification) {
                    return@runCatching writer.markVerificationFailed(
                        writer.finalVerificationFailureMessage()
                    )
                }
                return@runCatching finishIfPossible(writer)
            }
            if (allTargetedReady) {
                return@runCatching writer.currentOutput()
            }

            // Anything that didn't reach READY gets a clear FAILED so the UI
            // shows an error instead of a perpetual loading skeleton.
            val missing = stages.filter {
                writer.currentOutput().statusOf(it) != DeepReadSectionStatus.READY
            }
            markMissingFailed(writer, missing, "Agent 未按约定调用分段写入工具，该段未写入。")
            writer.currentOutput()
        }.fold(
            onSuccess = { output ->
                if (output.hasAnyReadySection() || output.isComplete()) Result.success(output) else Result.failure(
                    IllegalStateException(firstFailure(output) ?: "深度阅读生成失败")
                )
            },
            onFailure = { error ->
                // kotlin.runCatching swallows CancellationException, which would
                // otherwise unwind structured concurrency correctly. Re-throw it
                // so user-initiated cancel (UI navigation away, parent scope
                // cancel) is not silently turned into a Result.failure here.
                if (error is CancellationException) throw error
                Log.e(TAG, "deep read hidden run failed", error)
                val output = markMissingFailed(
                    writer = writer,
                    stages = stages,
                    message = error.message ?: error::class.simpleName.orEmpty(),
                )
                if (output.hasAnyReadySection()) Result.success(output) else Result.failure(error)
            },
        )
    }

    private suspend fun collectRun(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        assistant: me.rerere.rikkahub.data.model.Assistant,
        tools: List<me.rerere.ai.core.Tool>,
        writerToolNames: Set<String>,
        statusLabel: String,
    ): List<UIMessage> {
        var latest = messages
        suspend fun runWith(stream: Boolean) {
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant.copy(streamOutput = stream),
                memories = emptyList(),
                tools = tools,
                maxSteps = MAX_GENERATION_STEPS,
                processingStatus = MutableStateFlow(statusLabel),
                autoApproveTools = true,
                autoApproveHighRiskTools = false,
                autoApprovedToolNames = writerToolNames,
                invocationContext = ToolInvocationContext.Normal,
                conversation = null,
                inputTransformers = emptyList(),
                outputTransformers = emptyList(),
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) latest = chunk.messages
            }
        }
        try {
            runWith(stream = assistant.streamOutput)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(TAG, "deep read stream failed; retrying through non-stream GenerationHandler path", error)
            latest = messages
            runWith(stream = false)
        }
        return latest
    }

    private suspend fun finishIfPossible(writer: DeepReadSectionWriterTools): DeepReadOutput {
        val finish = writer.tools().first { it.name == "deep_read_finish" }
        finish.execute(kotlinx.serialization.json.buildJsonObject { })
        return writer.currentOutput()
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

    private fun buildPrompt(
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        existingOutput: DeepReadOutput,
        seedUrl: String?,
        scrapeWebAvailable: Boolean,
        prefetchedSources: List<DeepReadSource>,
        targetStage: DeepReadGenerationStage,
        playbookMarkdown: String,
    ): String = buildString {
        appendLine("今天日期：${LocalDate.now()}")
        appendLine("话题标题：$topicTitle")
        appendLine("目标段落：${stages.joinToString(" → ") { it.label }}")
        appendLine()
        appendLine("## Deep Read Playbook（本地规则，只读）")
        appendLine(playbookMarkdown.take(PLAYBOOK_PROMPT_LIMIT))
        seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            appendLine("用户指定来源 URL：$url")
            appendLine("该 URL 已在预抓阶段尝试读取，优先使用预抓正文；如下面预抓正文为空再 search_web/scrape_web 补充。")
        }
        appendLine()
        appendPrefetchedSources(
            sources = prefetchedSources,
            sourceLimit = targetStage.promptSourceLimit(),
            excerptLimit = targetStage.promptExcerptLimit(),
        )
        appendLine()
        appendArticleContext(existingOutput)
        appendLine()
        appendLine("## 研究顺序")
        appendLine("1. 上面已经为你预抓了 ${prefetchedSources.size} 条来源（含标题/URL/正文摘要/图片）。先把这些来源读懂，再判断是否够用。")
        appendLine("2. 仅在以下情况补充 search_web：")
        appendLine("   - 关键事实（发布时间、价格、官方表态、版本号等）在预抓来源中找不到或互相矛盾")
        appendLine("   - 用户指定 URL 的预抓正文为空，需要别的 query 命中该话题")
        appendLine("   - 你需要反面证据时（例如「辟谣」「不实」「未确认」）")
        if (scrapeWebAvailable) {
            appendLine("3. 仅在确认某个 URL 比预抓正文更详细时再 scrape_web；不要把预抓已有正文重抓一次。")
        } else {
            appendLine("3. 当前未暴露 scrape_web；只能基于预抓正文 + 必要时的 search_web 摘要写入。")
        }
        appendLine("4. 本轮只生成目标段落，不要改写或重写未列入目标的段落。")
        appendLine("5. 完成研究后立即调用对应 writer tool：")
        stages.forEach { stage -> appendLine("   - ${stage.writerToolName()}：${stage.label}") }
        appendLine("6. 图片只能从 image_candidates 中选择：需要头图或正文图时调用 deep_read_write_visuals。低置信图不要做头图；没有 hero 级候选就留空。")
        appendLine("7. 如果话题存在复杂因果、流程、利益相关方、系统结构或对比矩阵，调用 deep_read_write_diagram 提交结构化图解；不需要图解时完全不要调用。")
        appendLine("8. 全部 writer 完成后，系统会要求验真：调用 deep_read_verify_claims（至少 2 条带 evidence_urls 的核心声明），再 deep_read_finish。")
        appendLine()
        appendLine("## 段落要求")
        if (DeepReadGenerationStage.OVERVIEW in stages) {
            appendLine("- 概览：约 120-250 字中文杂志导语，说明事件是什么、为什么值得读、哪些事实已核查；完整句子优先，略超可以接受。")
        }
        if (DeepReadGenerationStage.NARRATIVE in stages) {
            appendLine("- 时间轴叙事：事件型写 timeline；观点/产品/人物型可写 core_points，但要有故事性和演化脉络。")
        }
        if (DeepReadGenerationStage.ANALYSIS in stages) {
            appendLine("- 深度分析：围绕核心分歧、各方立场、影响分析；这一段需要充分 reasoning，但不要输出 reasoning 给 UI。")
        }
        if (DeepReadGenerationStage.EXTENDED_READING in stages) {
            appendLine("- 扩展阅读：只放真实来源链接和真实图片资产。")
        }
        appendLine("- 视觉：头图必须来自候选池且 confidence=hero；inline 候选只能作为正文图。不得提交任意 URL、站点 logo、favicon、媒体图标或头像。")
        appendLine("- 图解：只提交 3-6 个短节点的 diagram spec；流程/因果只写主链路，避免网状交叉关系。禁止 raw SVG/HTML/JS/外链资源。图解不参与段落完成状态，不需要就隐藏。")
        appendLine()
        appendLine("正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。")
    }

    private fun StringBuilder.appendPrefetchedSources(
        sources: List<DeepReadSource>,
        sourceLimit: Int = PROMPT_SOURCE_LIMIT,
        excerptLimit: Int = PROMPT_SOURCE_EXCERPT_LIMIT,
    ) {
        appendLine("## 已预抓取来源（共 ${sources.size} 条，优先使用）")
        if (sources.isEmpty()) {
            appendLine("- 预抓返回空（理论上不会到这里，因为上层会先 fail）")
            return
        }
        sources.take(sourceLimit).forEachIndexed { index, source ->
            appendLine("### [${index + 1}] ${source.title}")
            if (source.url.isNotBlank()) appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            source.publishedAt?.takeIf { it.isNotBlank() }?.let { appendLine("- published_at: $it") }
            if (source.images.isNotEmpty()) appendLine("- images: ${source.images.joinToString(", ")}")
            val candidates = source.imageCandidates
                .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
                .take(5)
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
            val excerpt = source.content.take(excerptLimit).replace("\n", " ").trim()
            if (excerpt.isNotBlank()) appendLine("- excerpt: $excerpt")
            appendLine()
        }
    }

    private fun buildWriterReminder(stages: List<DeepReadGenerationStage>, pass: Int): String = buildString {
        appendLine("Supervisor reminder #${pass + 1}: 上一轮没有任何 deep_read_write_* 写入。")
        appendLine("UI 不会消费你的自由文本。请立刻继续研究缺口，然后调用以下 writer tool 中至少一个：")
        stages.forEach { stage -> appendLine("- ${stage.writerToolName()} for ${stage.label}") }
        appendLine("如果来源不足，只把当前段落写为 FAILED 的决定留给系统；不要用自由文本交差。")
    }

    private fun buildVerificationReminder(
        topicTitle: String,
        currentOutput: DeepReadOutput,
        prefetchedSources: List<DeepReadSource>,
        evidenceRegistry: DeepReadEvidenceRegistry,
        pass: Int,
        previousFailure: String?,
        previousPassMissingToolCall: Boolean,
    ): String = buildString {
        appendLine("Final verification reminder #$pass:")
        appendLine("话题「$topicTitle」的四个段落已经写入。请在最终 finish 前执行验真 skill。")
        if (previousPassMissingToolCall) {
            appendLine("上一轮没有调用 deep_read_verify_claims；本轮不要输出自由文本，")
            appendLine("必须调用 deep_read_verify_claims。")
        }
        previousFailure?.takeIf { it.isNotBlank() }?.let { reason ->
            appendLine("上一轮 deep_read_verify_claims 被拒绝：$reason")
            appendLine("请修正参数或必要时先改写被拒绝的段落，")
            appendLine("然后重新调用 deep_read_verify_claims。")
        }
        appendLine()
        appendPrefetchedSources(prefetchedSources)
        appendLine()
        appendArticleContext(currentOutput)
        appendLine()
        appendLine("## 当前允许 evidence_urls")
        evidenceRegistry.allowedUrls().take(40).forEach { url -> appendLine("- $url") }
        appendLine()
        appendLine("验真要求：")
        appendLine("1. 基于上面的当前稿件全文，抽取影响最大的 3-5 个核心子声明。")
        appendLine("2. 用已获得来源（含预抓 URL）核查这些声明；仅当声明在所有已抓来源中都找不到对应证据时，才再调 search_web / scrape_web 补充。")
        appendLine("3. 如果发现不确定或被证伪的声明，先调用对应 deep_read_write_* 修正段落。")
        appendLine("4. deep_read_verify_claims 至少提供 2 条带 evidence_urls 的核心声明。")
        appendLine("   visible_excerpt 必须摘录当前稿件原文（只允许空白/换行差异）；")
        appendLine("   evidence_excerpt 用对应来源正文/摘要里的原文或近似短句。")
        appendLine("5. evidence_urls **只能来自**：(a) 上面「已预抓取来源」列表中的 URL，")
        appendLine("   或 (b) 你在本轮 search_web/scrape_web 实际访问过且返回过 evidence_excerpt 的 URL。")
        appendLine("   未带来源 URL 的声明不会计入验真门槛。")
        appendLine("6. 然后调用 deep_read_verify_claims，最后调用 deep_read_finish。")
        appendLine()
        appendLine("仍然不要输出完整 JSON 或 Markdown 长文。")
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

    private fun StringBuilder.appendArticleContext(output: DeepReadOutput) {
        val current = output.withInferredSectionStates()
        appendLine("## 当前稿件正文（补段和最终验真必须覆盖）")
        appendLine(
            "section_states: " + DeepReadGenerationStage.entries.joinToString(", ") {
                "${it.name.lowercase()}=${current.statusOf(it).name.lowercase()}"
            }
        )
        if (!current.hasAnyReadySection() && current.summary.isBlank()) {
            appendLine("- 暂无已写入段落。")
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
        private const val MAX_VERIFICATION_PASSES = 3

        // Hard timeouts so a stuck generation cannot hang the hidden run forever.
        // Analysis gets a larger budget because it reads the most context and is
        // the stage most likely to time out on slower model/provider paths.
        private const val VERIFICATION_COLLECT_RUN_TIMEOUT_MS = 60_000L

        // Prompt-side caps on how many pre-fetched sources we surface and how much
        // of each source body we inline. Anything beyond this stays in the
        // prefetcher cache and can still be reached if the model needs it via
        // search_web on the same URL.
        private const val PROMPT_SOURCE_LIMIT = 12
        private const val PROMPT_SOURCE_EXCERPT_LIMIT = 2_000
        private const val PLAYBOOK_PROMPT_LIMIT = 12_000
        private val EVIDENCE_RECORDING_TOOL_NAMES = setOf("search_web", "scrape_web")
    }
}

private fun DeepReadGenerationStage.writerToolName(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "deep_read_write_overview"
    DeepReadGenerationStage.NARRATIVE -> "deep_read_write_narrative"
    DeepReadGenerationStage.ANALYSIS -> "deep_read_write_analysis"
    DeepReadGenerationStage.EXTENDED_READING -> "deep_read_write_extended_reading"
}

private fun DeepReadGenerationStage.promptSourceLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 6
    DeepReadGenerationStage.NARRATIVE -> 9
    DeepReadGenerationStage.ANALYSIS -> 8
    DeepReadGenerationStage.EXTENDED_READING -> 12
}

private fun DeepReadGenerationStage.promptExcerptLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 1_000
    DeepReadGenerationStage.NARRATIVE -> 1_400
    DeepReadGenerationStage.ANALYSIS -> 1_400
    DeepReadGenerationStage.EXTENDED_READING -> 700
}

private fun List<UIMessage>.latestAssistantText(): String =
    asReversed()
        .firstOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
        ?.toText()
        .orEmpty()

private fun DeepReadSectionWriterTools.finalVerificationFailureMessage(): String =
    lastVerificationFailureReason
        ?.takeIf { it.isNotBlank() }
        ?.let { "最终验真未通过：$it" }
        ?: if (verificationAttemptCount == 0) {
            "最终验真未完成：Agent 没有调用 deep_read_verify_claims。"
        } else {
            "最终验真未完成：Agent 已尝试验真，但没有通过验真门槛。"
        }
