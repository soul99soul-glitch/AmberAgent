package me.rerere.rikkahub.data.agent.board.hotlist.deepread

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agent.board.boardRequestBodies
import me.rerere.rikkahub.data.agent.board.boardRequestHeaders
import me.rerere.rikkahub.data.agent.board.hotlist.HotListRepository
import me.rerere.rikkahub.data.agent.board.hotlist.HotTopic
import me.rerere.rikkahub.data.agent.board.hotlist.presentationTitle
import me.rerere.rikkahub.data.agent.runtime.ToolInvocationContext
import me.rerere.rikkahub.data.agent.subagent.toIsolatedSubAgentSettings
import me.rerere.rikkahub.data.agent.tools.AgentToolSetFactory
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class DeepReadAgentRunManager(
    private val settingsStore: SettingsAggregator,
    private val generationHandler: GenerationHandler,
    private val hotListRepository: HotListRepository,
    private val toolSetFactory: AgentToolSetFactory,
    private val sourcePrefetcher: DeepReadSourcePrefetcher,
    private val appScope: AppScope,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val backgroundRuns = ConcurrentHashMap.newKeySet<String>()

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

        generateStages(
            topicId = topicId,
            topicTitle = topicTitle,
            stages = missing.ifEmpty { DeepReadGenerationStage.entries },
            seedUrl = seedUrl,
            force = force,
        )
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
                    if (stillMissing.isNotEmpty()) generateStages(topicId, topicTitle, stillMissing, seedUrl)
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
        val writer = DeepReadSectionWriterTools(
            repository = hotListRepository,
            topicId = topicId,
            topicTitle = topicTitle,
            allowTitleFallback = seedUrl.isNullOrBlank(),
        )
        val writerTools = writer.tools(stages = stages.toSet())
        val tools = toolSetFactory.forDeepRead(settings, writerTools)
        val scrapeWebAvailable = tools.any { it.name == "scrape_web" }
        val hiddenSettings = settings.toIsolatedSubAgentSettings()
        val assistant = DeepReadHiddenAssistantFactory.create(settings)

        val prefetchedSources = sourcePrefetcher.collect(
            topicId = topicId,
            topicTitle = topicTitle,
            seedUrl = seedUrl,
        )
        if (prefetchedSources.isEmpty()) {
            val message = "没有抓到足够的来源，无法生成深度阅读。请检查搜索源或稍后重试。"
            Log.w(TAG, "deep read prefetch returned no sources for topic=$topicId")
            return Result.failure(IllegalStateException(message))
        }
        Log.i(TAG, "deep read prefetch ready: ${prefetchedSources.size} sources for topic=$topicId")

        return runCatching {
            writer.markRunning(stages)
            var messages = listOf(
                UIMessage.user(
                    buildPrompt(
                        topicTitle = topicTitle,
                        stages = stages,
                        existingOutput = writer.currentOutput(),
                        seedUrl = seedUrl,
                        scrapeWebAvailable = scrapeWebAvailable,
                        prefetchedSources = prefetchedSources,
                    )
                )
            )
            repeat(MAX_SUPERVISOR_PASSES) { pass ->
                val beforeWrites = writer.writeCount
                messages = withTimeout(collectRunTimeoutFor(stages)) {
                    collectRun(
                        settings = hiddenSettings,
                        model = model,
                        messages = messages,
                        assistant = assistant,
                        tools = tools,
                        writerToolNames = writerTools.map { it.name }.toSet(),
                        statusLabel = "深度阅读 ${stages.joinToString(" / ") { it.label }}",
                    )
                }
                val current = writer.currentOutput()
                if (stages.all { current.statusOf(it) == DeepReadSectionStatus.READY }) {
                    if (writer.verificationCount == 0) {
                        messages = messages + UIMessage.user(buildVerificationReminder(topicTitle, current))
                        messages = withTimeout(VERIFICATION_COLLECT_RUN_TIMEOUT_MS) {
                            collectRun(
                                settings = hiddenSettings,
                                model = model,
                                messages = messages,
                                assistant = assistant,
                                tools = tools,
                                writerToolNames = writerTools.map { it.name }.toSet(),
                                statusLabel = "深度阅读 验真",
                            )
                        }
                    }
                    if (!writer.hasFreshVerification) {
                        return@runCatching writer.markVerificationFailed("验真未完成：Agent 没有在最终写入后调用 deep_read_verify_claims。")
                    }
                    return@runCatching finishIfPossible(writer)
                }
                if (writer.writeCount == beforeWrites) {
                    messages = messages + UIMessage.user(buildWriterReminder(stages, pass))
                }
            }

            val current = writer.currentOutput()
            val missing = stages.filter { current.statusOf(it) != DeepReadSectionStatus.READY }
            markMissingFailed(writer, missing, "Agent 未按约定调用分段写入工具，该段未写入。")
            writer.currentOutput()
        }.fold(
            onSuccess = { output ->
                if (output.hasAnyReadySection() || output.isComplete()) Result.success(output) else Result.failure(
                    IllegalStateException(firstFailure(output) ?: "深度阅读生成失败")
                )
            },
            onFailure = { error ->
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
    ): String = buildString {
        appendLine("今天日期：${LocalDate.now()}")
        appendLine("话题标题：$topicTitle")
        appendLine("目标段落：${stages.joinToString(" → ") { it.label }}")
        seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            appendLine("用户指定来源 URL：$url")
            appendLine("该 URL 已在预抓阶段尝试读取，优先使用预抓正文；如下面预抓正文为空再 search_web/scrape_web 补充。")
        }
        appendLine()
        appendPrefetchedSources(prefetchedSources)
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
        appendLine("6. 全部 writer 完成后，系统会要求验真：调用 deep_read_verify_claims（至少 2 条带 evidence_urls 的核心声明），再 deep_read_finish。")
        appendLine()
        appendLine("## 段落要求")
        if (DeepReadGenerationStage.OVERVIEW in stages) {
            appendLine("- 概览：250-700 字中文杂志导语，说明事件是什么、为什么值得读、哪些事实已核查。")
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
        appendLine()
        appendLine("正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。")
    }

    private fun StringBuilder.appendPrefetchedSources(sources: List<DeepReadSource>) {
        appendLine("## 已预抓取来源（共 ${sources.size} 条，优先使用）")
        if (sources.isEmpty()) {
            appendLine("- 预抓返回空（理论上不会到这里，因为上层会先 fail）")
            return
        }
        sources.take(PROMPT_SOURCE_LIMIT).forEachIndexed { index, source ->
            appendLine("### [${index + 1}] ${source.title}")
            if (source.url.isNotBlank()) appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            source.publishedAt?.takeIf { it.isNotBlank() }?.let { appendLine("- published_at: $it") }
            if (source.images.isNotEmpty()) appendLine("- images: ${source.images.joinToString(", ")}")
            val excerpt = source.content.take(PROMPT_SOURCE_EXCERPT_LIMIT).replace("\n", " ").trim()
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

    private fun buildVerificationReminder(topicTitle: String, currentOutput: DeepReadOutput): String = buildString {
        appendLine("Final verification reminder:")
        appendLine("话题「$topicTitle」的四个段落已经写入。请在最终 finish 前执行验真 skill。")
        appendLine()
        appendArticleContext(currentOutput)
        appendLine()
        appendLine("验真要求：")
        appendLine("1. 基于上面的当前稿件全文，抽取影响最大的 3-5 个核心子声明。")
        appendLine("2. 用已获得来源（含预抓 URL）核查这些声明；仅当声明在所有已抓来源中都找不到对应证据时，才再调 search_web / scrape_web 补充。")
        appendLine("3. 如果发现不确定或被证伪的声明，先调用对应 deep_read_write_* 修正段落。")
        appendLine("4. deep_read_verify_claims 至少提供 2 条带 evidence_urls 的核心声明。evidence_urls **只能来自**：(a) 上面「已预抓取来源」列表中的 URL（只要该声明在该来源正文里有依据），或 (b) 你在本轮 search_web/scrape_web 实际访问过的 URL。**不要写未访问过的猜测 URL**。未带来源 URL 的声明不会计入验真门槛。")
        appendLine("5. 然后调用 deep_read_verify_claims，最后调用 deep_read_finish。")
        appendLine()
        appendLine("仍然不要输出完整 JSON 或 Markdown 长文。")
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
            appendLine("overview.summary: ${current.summary.take(1_000)}")
        }
        if (current.keyEntities.isNotEmpty()) {
            appendLine("overview.key_entities: ${current.keyEntities.take(12).joinToString(" / ")}")
        }
        current.timeline.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { timeline ->
            appendLine("narrative.timeline:")
            timeline.forEachIndexed { index, event ->
                appendLine("- ${index + 1}. ${event.date}: ${event.event.take(600)}")
            }
        }
        current.corePoints.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { points ->
            appendLine("narrative.core_points:")
            points.forEachIndexed { index, point ->
                appendLine("- ${index + 1}. ${point.point.take(300)} ${point.supporting.orEmpty().take(500)}")
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
                appendLine("- core_dispute: ${it.take(700)}")
            }
            current.analysis.perspectives.take(8).forEach { perspective ->
                appendLine("- perspective(${perspective.holder.orEmpty()}): ${perspective.viewpoint.take(700)}")
            }
            current.analysis.implications?.takeIf { it.isNotBlank() }?.let {
                appendLine("- implications: ${it.take(900)}")
            }
            current.analysis.quotes.take(6).forEach { quote ->
                appendLine("- quote(${quote.attribution.orEmpty()}): ${quote.text.take(300)}")
            }
        }
        current.extendedReading.take(10).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("extended_reading:")
            links.forEach { link ->
                appendLine("- ${link.title.take(180)} | ${link.source.orEmpty()} | ${link.url}")
            }
        }
        current.references.take(12).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("references:")
            links.forEach { link ->
                appendLine("- ${link.title.take(180)} | ${link.source.orEmpty()} | ${link.url}")
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

    private fun collectRunTimeoutFor(stages: List<DeepReadGenerationStage>): Long =
        COLLECT_RUN_BASE_TIMEOUT_MS + stages.size * COLLECT_RUN_PER_STAGE_TIMEOUT_MS

    companion object {
        private const val TAG = "DeepReadAgentRunManager"
        private const val MAX_GENERATION_STEPS = 32
        private const val MAX_SUPERVISOR_PASSES = 2

        // Hard timeouts so a stuck generation cannot hang the hidden run forever.
        // Total budget per supervisor pass scales with how many sections the model
        // needs to produce in this single collectRun call. Verification is a fixed
        // short follow-up pass that should not need long search loops.
        private const val COLLECT_RUN_BASE_TIMEOUT_MS = 30_000L
        private const val COLLECT_RUN_PER_STAGE_TIMEOUT_MS = 60_000L
        private const val VERIFICATION_COLLECT_RUN_TIMEOUT_MS = 60_000L

        // Prompt-side caps on how many pre-fetched sources we surface and how much
        // of each source body we inline. Anything beyond this stays in the
        // prefetcher cache and can still be reached if the model needs it via
        // search_web on the same URL.
        private const val PROMPT_SOURCE_LIMIT = 12
        private const val PROMPT_SOURCE_EXCERPT_LIMIT = 2_000
    }
}

private fun DeepReadGenerationStage.writerToolName(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "deep_read_write_overview"
    DeepReadGenerationStage.NARRATIVE -> "deep_read_write_narrative"
    DeepReadGenerationStage.ANALYSIS -> "deep_read_write_analysis"
    DeepReadGenerationStage.EXTENDED_READING -> "deep_read_write_extended_reading"
}
