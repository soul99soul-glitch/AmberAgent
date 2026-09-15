package app.amber.feature.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.provider.ProviderCatalog
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.store.RoomAgentEventStore
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.appLocale
import app.amber.feature.live.bubble.LiveBubbleContent
import app.amber.feature.live.bubble.LiveBubbleWindow
import app.amber.feature.ui.theme.AmberAgentTheme

class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val appScope: AppScope,
    private val agentRunner: AgentRunner,
    private val eventStore: RoomAgentEventStore,
    private val usageStore: LiveUsageStore,
) {
    private val _state = MutableStateFlow(
        LiveModeUiState(statusText = context.getString(R.string.live_empty_not_started)),
    )
    val state: StateFlow<LiveModeUiState> = _state.asStateFlow()

    private val analyzer = LiveAnalyzer(providerCatalog, context)
    private val screenshotter = LiveScreenshotter(context)
    private val bubble = LiveBubbleWindow()

    private var loopJob: Job? = null
    private var eventJob: Job? = null
    private var analysisJob: Job? = null
    private val analysisGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var engine: LiveEngine? = null
    private var pendingSnapshot: LiveScreenSnapshot? = null
    private var focusInstruction: String = ""

    @Volatile
    private var activeRunId: AgentRunId? = null

    @Volatile
    private var screenDirty: Boolean = true // 启动先看一眼

    fun start() {
        if (loopJob?.isActive == true) {
            resume()
            return
        }
        val liveSetting = settingsStore.settingsFlow.value.agentRuntime.liveMode
        engine = LiveEngine(
            stableDelayMs = liveSetting.stableDelayMs.coerceIn(500L, 5_000L),
            minAnalysisIntervalMs = liveSetting.minAnalysisIntervalMs.coerceIn(5_000L, 30_000L),
            backoffMs = MODEL_BUSY_BACKOFF_MS,
        )
        screenDirty = true
        _state.value = LiveModeUiState(
            active = true,
            statusText = context.getString(R.string.live_master_enabled),
        )
        appScope.launch(Dispatchers.IO) {
            restoreLatestCard()
            // 保留清扫（蓝图 v3 §6）：终态 run 与过期事件同窗口清理，best-effort。
            val cutoff = System.currentTimeMillis() - LIVE_ARTIFACT_RETENTION_MS
            runCatching {
                eventStore.deleteTerminalRunsOfAgentOlderThan(LiveTurnDescriptor.ID.value, cutoff)
                eventStore.deleteEventsOfTypeOlderThan(LiveEventPayload.AnalysisCompleted.TYPE, cutoff)
            }
            // 隐私边界（蓝图 v3 §7.2 P0-6）：默认不留存原始屏幕——清掉旧版本
            // 可能遗留的截图缓存（P0 不开放截图，此目录不应存在内容）。
            runCatching { java.io.File(context.cacheDir, "live").deleteRecursively() }
        }
        eventJob = appScope.launch {
            AmberAccessibilityService.screenEvents.collect { event ->
                if (event.packageName != context.packageName) screenDirty = true
            }
        }
        loopJob = appScope.launch(Dispatchers.Main.immediate) { runLoop() }
    }

    /** 冷读取（蓝图 v3 §7.2 P0-2 验收链）：从事件库恢复最近一次伴随卡片。
     *  进程内已有卡片时不覆盖；恢复的 cardSignature 与下一帧屏幕签名比对，
     *  屏幕已不同则经 cardStale 自然呈现"屏幕已变化"。 */
    private suspend fun restoreLatestCard() {
        val entity = runCatching {
            eventStore.latestEventOfType(LiveEventPayload.AnalysisCompleted.TYPE)
        }.getOrNull() ?: return
        val payload = runCatching {
            JsonInstant.decodeFromString<LiveEventPayload.AnalysisCompleted>(entity.payload)
        }.getOrNull() ?: return
        _state.update {
            // 回填前确认会话仍在（总检查 #3）：start 后极短窗口内 stop 时不得复活卡片。
            if (!it.active || it.card != null) it else it.copy(
                card = LiveModeCard(
                    watching = payload.watching,
                    keyPoints = payload.keyPoints,
                    suggestions = payload.suggestions,
                ),
                cardSignature = payload.screenSignature,
                // 首帧快照可能已写入当前现场——只在现场为空时回填，不覆盖新鲜元数据。
                currentPackage = it.currentPackage.ifBlank { payload.packageName },
                currentAppLabel = it.currentAppLabel.ifBlank { payload.appLabel },
                currentTitle = it.currentTitle.ifBlank { payload.title },
                lastUpdatedAtMillis = entity.ts,
            )
        }
    }

    fun pause() {
        analysisGeneration.incrementAndGet()
        _state.update {
            it.copy(
                paused = true,
                analyzing = false,
                requestedAction = "",
                statusText = context.getString(R.string.live_master_paused),
            )
        }
        activeRunId?.let(agentRunner::cancel)
        activeRunId = null
        analysisJob?.cancel()
    }

    fun resume() {
        if (loopJob?.isActive != true) {
            start()
            return
        }
        _state.update {
            it.copy(
                active = true,
                paused = false,
                statusText = context.getString(R.string.live_master_reading),
            )
        }
    }

    fun stop() {
        analysisGeneration.incrementAndGet()
        loopJob?.cancel()
        loopJob = null
        eventJob?.cancel()
        eventJob = null
        activeRunId?.let(agentRunner::cancel)
        activeRunId = null
        analysisJob?.cancel()
        analysisJob = null
        bubble.hide()
        engine = null
        pendingSnapshot = null
        screenDirty = true
        focusInstruction = ""
        _state.value = LiveModeUiState(
            statusText = context.getString(R.string.live_empty_not_started),
        )
    }

    fun refreshNow() {
        if (_state.value.paused) {
            _state.update { it.copy(statusText = context.getString(R.string.live_master_paused)) }
            return
        }
        val snapshot = pendingSnapshot
        if (snapshot == null) {
            _state.update { it.copy(statusText = context.getString(R.string.live_result_screen_unclear)) }
        } else {
            analyzeSnapshot(snapshot, force = true)
        }
    }

    fun submitFocusInstruction(instruction: String) {
        val normalized = instruction.trim()
        if (normalized.isBlank()) return
        focusInstruction = normalized.take(240)
        val actionLabel = liveActionLabel(focusInstruction)
        _state.update {
            it.copy(
                currentFocus = focusInstruction,
                requestedAction = actionLabel,
                completedAction = "",
                statusText = ongoingStatus(actionLabel),
            )
        }
        if (!_state.value.paused) {
            refreshNow()
        }
    }

    fun exportCurrentCard(): String? {
        val current = state.value
        val card = current.card ?: return null
        return buildString {
            appendLine(context.getString(R.string.live_companion_title))
            appendLine()
            appendLine("${context.getString(R.string.live_current_app)}: ${current.currentAppLabel.ifBlank { current.currentPackage }}")
            if (current.currentTitle.isNotBlank()) {
                appendLine("${context.getString(R.string.live_result_what_is_visible)}: ${current.currentTitle}")
            }
            if (current.currentFocus.isNotBlank()) {
                appendLine("${context.getString(R.string.live_result_basis)}: ${current.currentFocus}")
            }
            if (current.completedAction.isNotBlank()) {
                appendLine(
                    context.getString(
                        R.string.live_result_title_custom,
                        localizedActionLabel(current.completedAction),
                    ),
                )
            }
            appendLine()
            appendLine("${context.getString(R.string.live_result_what_is_visible)}: ${card.watching}")
            if (card.keyPoints.isNotEmpty()) {
                appendLine("${context.getString(R.string.live_result_key_points)}:")
                card.keyPoints.forEach { appendLine("- $it") }
            }
            if (card.suggestions.isNotEmpty()) {
                appendLine("${context.getString(R.string.live_result_what_to_do)}:")
                card.suggestions.forEach { appendLine("- $it") }
            }
        }.trim()
    }

    private suspend fun runLoop() {
        while (true) {
            val settings = settingsStore.settingsFlow.value
            val liveSetting = settings.agentRuntime.liveMode
            syncBubble(liveSetting)
            if (!liveSetting.enabled) {
                // 设置页开关关闭 = 停止一切（与 stop() 的取消语义对齐）：
                // 在飞 run 取消、晚到结果经 generation 兜底不回写（总检查 #2）。
                if (_state.value.analyzing || activeRunId != null) {
                    analysisGeneration.incrementAndGet()
                    activeRunId?.let(agentRunner::cancel)
                    activeRunId = null
                    analysisJob?.cancel()
                }
                _state.update {
                    it.copy(
                        active = false,
                        paused = false,
                        analyzing = false,
                        requestedAction = "",
                        statusText = context.getString(R.string.live_master_not_enabled),
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                delay(1_000L)
                continue
            }
            if (_state.value.paused) {
                delay(500L)
                continue
            }
            val model = analyzer.resolveModel(settings)
            if (model == null) {
                _state.update {
                    it.copy(
                        noModelConfigured = true,
                        needsAccessibility = false,
                        analyzing = false,
                        statusText = context.getString(R.string.live_model_required_title),
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                delay(1_500L)
                continue
            }
            val service = AmberAccessibilityService.getActiveService()
            if (service == null) {
                val serviceEnabled = isAmberAccessibilityServiceEnabled()
                _state.update {
                    it.copy(
                        needsAccessibility = !serviceEnabled,
                        noModelConfigured = false,
                        analyzing = false,
                        statusText = if (serviceEnabled) {
                            context.getString(R.string.live_master_reading)
                        } else {
                            context.getString(R.string.live_accessibility_required_title)
                        },
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                delay(1_500L)
                continue
            }

            // 事件驱动：屏幕没动（无事件）且引擎也无待办时，跳过捕获
            val engine = this.engine ?: break
            val tickInterval = liveSetting.refreshIntervalMs.coerceIn(1_000L, 5_000L)
            if (!screenDirty && pendingSnapshot == null) {
                delay(tickInterval)
                continue
            }

            if (screenDirty) {
                screenDirty = false
                val snapshot = service.captureLiveUiSnapshot(
                    ownPackageName = context.packageName,
                    maxNodes = liveSetting.maxNodes.coerceIn(40, 260),
                )
                if (snapshot == null) {
                    _state.update {
                        it.copy(
                            needsAccessibility = false, noModelConfigured = false,
                            analyzing = false,
                            statusText = context.getString(R.string.live_result_screen_unclear),
                        )
                    }
                    delay(tickInterval)
                    continue
                }
                val now = System.currentTimeMillis()
                if (engine.onScreenSignature(snapshot.stableHash, now)) {
                    pendingSnapshot = snapshot
                    _state.update {
                        val stale = it.card != null && it.cardSignature != null &&
                            it.cardSignature != snapshot.stableHash
                        it.copy(
                            active = true, needsAccessibility = false, noModelConfigured = false,
                            currentPackage = snapshot.packageName,
                            currentAppLabel = snapshot.appLabel,
                            currentTitle = snapshot.title,
                            lastSnapshotHash = snapshot.stableHash,
                            statusText = if (stale) {
                                context.getString(R.string.live_result_screen_changed)
                            } else {
                                readingStatus(snapshot.appLabel.ifBlank { snapshot.packageName })
                            },
                        )
                    }
                }
            }

            // P0 手动语义（蓝图 v3 §7.2 P0-7）：屏幕事件只驱动快照与状态呈现，
            // 不自动发起模型调用；分析仅由 refreshNow / submitFocusInstruction 触发。
            delay(tickInterval)
        }
    }

    private fun analyzeSnapshot(snapshot: LiveScreenSnapshot, force: Boolean) {
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        when (val d = engine.decide(now, force)) {
            is LiveEngine.Decision.Wait -> {
                if (d.reason == "backoff") {
                    _state.update {
                        it.copy(
                            statusText = context.getString(R.string.live_master_model_busy),
                            nextAnalysisAfterMillis = engine.backoffUntilMillis(),
                        )
                    }
                    return
                }
                if (!force) return
            }
            LiveEngine.Decision.Analyze -> Unit
        }
        val model = analyzer.resolveModel(settings)
        if (model == null) {
            _state.update {
                it.copy(
                    noModelConfigured = true,
                    statusText = context.getString(R.string.live_model_required_title),
                )
            }
            return
        }

        // 场景默认动作：显式指令优先，其次场景画像，最后通用屏幕分析
        val sceneDefault = LiveScenes.defaultActionLabel(LiveScenes.classify(snapshot.packageName))
        val actionLabel = if (focusInstruction.isBlank()) {
            sceneDefault ?: DEFAULT_ACTION_LABEL
        } else {
            liveActionLabel(focusInstruction)
        }

        val generation = analysisGeneration.incrementAndGet()
        engine.onAnalysisStarted(now)
        activeRunId?.let(agentRunner::cancel)
        activeRunId = null
        analysisJob?.cancel()
        analysisJob = appScope.launch(Dispatchers.IO) {
            try {
                _state.update {
                    if (generation != analysisGeneration.get()) return@update it
                    it.copy(
                        analyzing = true,
                        requestedAction = if (actionLabel == DEFAULT_ACTION_LABEL) it.requestedAction else actionLabel,
                        completedAction = "",
                        statusText = ongoingStatus(actionLabel),
                        error = null,
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                // 一次分析 = 一个 run（蓝图 v3 §7.1 执行顺序链）：run 只包推理，
                // 采集/门控在本域层；P0 不开放截图分析（P1-8 恢复时 input 加 mode）。
                val input = LiveTurnInput(
                    packageName = snapshot.packageName,
                    appLabel = snapshot.appLabel,
                    title = snapshot.title,
                    contentText = snapshot.contentText.ifBlank { snapshot.visibleText },
                    uiTree = snapshot.uiTree,
                    screenSignature = snapshot.stableHash,
                    focus = focusInstruction,
                    actionLabel = actionLabel,
                    localeTag = context.appLocale().toLanguageTag(),
                    capturedAtMillis = snapshot.capturedAtMillis,
                )
                val handle = agentRunner.launch(LiveTurnDescriptor.ID, input).getOrThrow()
                // 代数兜底：launch 到注册之间取消方（pause/stop/新分析）可能已落空，
                // generation 不匹配 = 本 run 已被放弃，立即取消，晚到结果不得入库。
                if (generation != analysisGeneration.get()) {
                    agentRunner.cancel(handle.runId)
                    throw CancellationException("live analysis superseded")
                }
                activeRunId = handle.runId
                val terminal = agentRunner.observe(handle.runId).first { it.status.isTerminal }
                if (activeRunId == handle.runId) activeRunId = null
                val artifact = terminal.artifact as? LiveTurnArtifact
                if (terminal.status != RunStatus.COMPLETED || artifact == null) {
                    throw terminal.error ?: IllegalStateException("Live run ended ${terminal.status}")
                }
                // 模型调用已发生即记账（与结果是否回写无关）；usage 缺失按 0 值累加、次数 +1。
                // 记账失败不得把成功分析误报为失败（总检查 #4），故 runCatching 隔离。
                runCatching {
                    withContext(NonCancellable) {
                        usageStore.accumulate(artifact.promptTokens, artifact.completionTokens, artifact.cachedTokens)
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    // 结果有效性检查（蓝图 v3 §7.1 执行顺序链末端）：
                    // generation 未变 + 屏幕上下文自分析启动后未变 + 结果未超期，三者同时成立才回写。
                    val finishedAt = System.currentTimeMillis()
                    val contextCurrent = pendingSnapshot?.stableHash == snapshot.stableHash
                    val withinTtl = finishedAt - now <= ANALYSIS_RESULT_TTL_MS
                    when {
                        generation != analysisGeneration.get() -> Unit
                        !contextCurrent || !withinTtl -> {
                            _state.update {
                                it.copy(
                                    analyzing = false,
                                    requestedAction = "",
                                    completedAction = "",
                                    statusText = context.getString(R.string.live_result_stale_dropped),
                                )
                            }
                        }
                        else -> {
                            engine.onAnalysisSucceeded(snapshot.stableHash)
                            _state.update {
                                it.copy(
                                    analyzing = false,
                                    card = artifact.card,
                                    cardSignature = snapshot.stableHash,
                                    currentPackage = snapshot.packageName,
                                    currentAppLabel = snapshot.appLabel,
                                    currentTitle = snapshot.title,
                                    requestedAction = "",
                                    completedAction = actionLabel,
                                    statusText = artifact.degradedReason ?: doneStatus(actionLabel),
                                    error = null,
                                    lastUpdatedAtMillis = finishedAt,
                                    nextAnalysisAfterMillis = 0L,
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Live analysis failed", error)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == analysisGeneration.get()) {
                        val failure = LiveFailure.from(
                            context = context,
                            error = error,
                            actionLabel = localizedActionLabel(actionLabel),
                        )
                        if (failure.retryable) engine.onRetryableFailure(System.currentTimeMillis())
                        _state.update {
                            it.copy(
                                analyzing = false,
                                requestedAction = "",
                                statusText = failure.statusText,
                                error = failure.message,
                                completedAction = "",
                                nextAnalysisAfterMillis = if (failure.retryable) engine.backoffUntilMillis() else 0L,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun liveActionLabel(instruction: String): String {
        val text = instruction.trim()
        return when {
            text.isBlank() -> DEFAULT_ACTION_LABEL
            "重点" in text -> "找重点"
            "总结" in text || "摘要" in text -> "总结"
            "下一步" in text || "怎么做" in text -> "找下一步"
            "风险" in text || "问题" in text -> "查风险"
            "回复" in text || "回话" in text -> "写回复"
            else -> text.take(12)
        }
    }

    private fun ongoingStatus(actionLabel: String): String =
        context.getString(R.string.live_action_running, localizedActionLabel(actionLabel))

    private fun doneStatus(actionLabel: String): String =
        context.getString(R.string.live_action_received, localizedActionLabel(actionLabel))

    private fun readingStatus(appLabel: String): String =
        context.getString(
            R.string.live_master_target_mode,
            appLabel,
            context.getString(R.string.live_master_reading),
        )

    private fun localizedActionLabel(actionLabel: String): String = when (actionLabel) {
        "屏幕分析" -> context.getString(R.string.live_action_screen_analysis)
        "找重点" -> context.getString(R.string.live_action_find_focus)
        "总结" -> context.getString(R.string.live_action_summarize)
        "找下一步" -> context.getString(R.string.live_action_find_next_step)
        "查风险" -> context.getString(R.string.live_action_check_risks)
        "写回复" -> context.getString(R.string.live_action_write_reply)
        else -> actionLabel
    }

    private fun isAmberAccessibilityServiceEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName
        }
    }

    private data class LiveFailure(
        val statusText: String,
        val message: String,
        val retryable: Boolean,
    ) {
        companion object {
            fun from(context: Context, error: Throwable, actionLabel: String): LiveFailure {
                val raw = (error.message ?: error.toString()).trim()
                val lower = raw.lowercase()
                val retryHint = context.getString(R.string.live_action_retry_hint, actionLabel)
                return when {
                    "503" in raw ||
                        "service_unavailable" in lower ||
                        "too busy" in lower -> LiveFailure(
                            statusText = context.getString(R.string.live_master_model_busy),
                            message = retryHint,
                            retryable = true,
                        )

                    "timeout" in lower || "timed out" in lower -> LiveFailure(
                        statusText = context.getString(R.string.live_master_model_busy),
                        message = retryHint,
                        retryable = true,
                    )

                    else -> LiveFailure(
                        statusText = context.getString(R.string.live_master_analysis_failed),
                        message = raw.ifBlank {
                            context.getString(R.string.live_master_analysis_failed)
                        }.take(220),
                        retryable = false,
                    )
                }
            }
        }
    }

    /** P0 收敛为纯复制（蓝图 v3 §7.2 P0-5）：写入仲裁契约落地前不触碰目标应用
     *  输入框；P1 起按白名单逐 App 恢复填入，恢复时需带目标绑定与写入前校验。 */
    fun fillCurrentDraft(): LiveFillResult {
        val card = _state.value.card ?: return LiveFillResult.NO_DRAFT
        val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: card.watching.takeIf { it.isNotBlank() }
            ?: return LiveFillResult.NO_DRAFT
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: return LiveFillResult.NO_DRAFT
        clipboard.setPrimaryClip(ClipData.newPlainText("amber-live-draft", draft))
        return LiveFillResult.COPIED
    }

    /** 每个 runLoop tick 调一次：根据状态决定气泡显隐。仅主线程。 */
    private fun syncBubble(liveSetting: LiveModeSetting) {
        val service = AmberAccessibilityService.getActiveService()
        if (service == null ||
            !liveSetting.enabled ||
            !liveSetting.bubbleEnabled ||
            !_state.value.active ||
            service.activePackageName() == context.packageName
        ) {
            bubble.hide()
            return
        }
        bubble.show(service) {
            AmberAgentTheme {
                val uiState by state.collectAsState()
                LiveBubbleContent(
                    state = uiState,
                    onFillDraft = ::fillCurrentDraft,
                    onRefresh = ::refreshNow,
                    onStop = ::stop,
                    onDrag = bubble::moveBy,
                    onDragEnd = bubble::snapToEdge,
                    onSizeChanged = bubble::requestReclamp,
                )
            }
        }
    }

    companion object {
        private const val TAG = "LiveModeManager"
        private const val DEFAULT_ACTION_LABEL = "屏幕分析"
        private const val MODEL_BUSY_BACKOFF_MS = 30_000L

        /** 分析结果回写的有效期：超时即认为结果已脱离现场，丢弃而非覆盖。 */
        private const val ANALYSIS_RESULT_TTL_MS = 60_000L

        /** Live run/事件的保留窗口（蓝图 v3 §6）：30 天，伴随启动时 best-effort 清扫。 */
        private const val LIVE_ARTIFACT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

enum class LiveFillResult { FILLED, COPIED, NO_DRAFT }
