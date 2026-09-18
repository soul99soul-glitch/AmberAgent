package app.amber.feature.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
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
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.store.RoomAgentEventStore
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.LiveFillOutcome
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.appLocale
import app.amber.core.utils.sendNotification
import app.amber.feature.bubble.BubbleWindow
import app.amber.feature.live.bubble.LiveBubbleContent
import app.amber.feature.ui.theme.AmberAgentTheme

class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val appScope: AppScope,
    private val agentRunner: AgentRunner,
    private val eventStore: RoomAgentEventStore,
    private val usageStore: LiveUsageStore,
    private val taskBubble: app.amber.feature.bubble.AgentTaskBubbleController,
    private val cardStore: LiveCardStore,
) {
    private val _state = MutableStateFlow(
        LiveModeUiState(statusText = context.getString(R.string.live_empty_not_started)),
    )
    val state: StateFlow<LiveModeUiState> = _state.asStateFlow()

    /** 气泡展开态由 Manager 持有（原 UI 侧 remember 在窗口重挂/任务气泡让位往返后会丢失）。
     *  lastSeenMillis 同理上提：跨重挂的"新鲜结果"判定与查看指标不再重置。 */
    private val _bubbleExpanded = MutableStateFlow(false)
    val bubbleExpanded: StateFlow<Boolean> = _bubbleExpanded.asStateFlow()

    @Volatile
    private var bubbleLastSeenMillis: Long = 0L

    private val analyzer = LiveAnalyzer(providerCatalog, context)
    private val screenshotter = LiveScreenshotter(context)
    private val bubble = BubbleWindow()

    private var loopJob: Job? = null
    private var eventJob: Job? = null
    private var bubbleWatchJob: Job? = null
    private var analysisJob: Job? = null
    private val analysisGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var engine: LiveEngine? = null
    private var pendingSnapshot: LiveScreenSnapshot? = null
    private var focusInstruction: String = ""

    @Volatile
    private var activeRunId: AgentRunId? = null

    @Volatile
    private var lastStreamEmitAt: Long = 0L

    /** 填入二次确认窗口（包名+草稿哈希+时间戳）；熔断集（回读不符的包名，进程级）。 */
    @Volatile
    private var pendingFillConfirm: Triple<String, Int, Long>? = null
    private val fillDenylist = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 自动建议预算账本：包名 → 最近一小时触发时间戳队列（P2 推理门）。 */
    private val autoSuggestTimestamps = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()

    /** 已上报"查看"的结果时间戳（去重；单调不回落——同结果被反复展开只计一次）。 */
    @Volatile
    private var lastSuggestViewedAt: Long = 0L

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
            // 隐私边界（蓝图 v3 §7.2 P0-6）：清旧版本遗留与孤儿截图残留
            // （正常路径每个 run 结束即删自己的文件，这里是兜底清扫）。
            screenshotter.cleanupOrphans()
        }
        eventJob = appScope.launch {
            AmberAccessibilityService.screenEvents.collect { event ->
                if (event.packageName != context.packageName) screenDirty = true
            }
        }
        // 任务气泡亮起时立即让位（不等 runLoop 下个 tick，避免双窗叠放数秒）
        bubbleWatchJob = appScope.launch(Dispatchers.Main.immediate) {
            taskBubble.visible.collect {
                syncBubble(settingsStore.settingsFlow.value.agentRuntime.liveMode)
            }
        }
        loopJob = appScope.launch(Dispatchers.Main.immediate) { runLoop() }
    }

    /** 流式预览回调（run 内 handler 经 LiveTurnAgent 注入调用；线程随意——
     *  StateFlow 更新线程安全，80ms 节流挡重组洪峰）。 */
    fun onLiveStreamDelta(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastStreamEmitAt < STREAM_EMIT_INTERVAL_MS) return
        lastStreamEmitAt = now
        _state.update {
            if (it.analyzing) it.copy(streamingText = text.take(STREAM_TEXT_LIMIT)) else it
        }
    }

    /** 自动建议结果被查看（气泡展开）——P2 对照指标"有效帮助"。
     *  以结果为单位去重（P2 终审 #3：气泡重组会重置 UI 侧 lastSeenMillis，
     *  同一结果往返展开不得重复计数）；atMillis=结果的 lastUpdatedAtMillis。 */
    fun markSuggestViewed(atMillis: Long) {
        if (atMillis <= lastSuggestViewedAt) return
        lastSuggestViewedAt = atMillis
        appScope.launch(Dispatchers.IO) {
            runCatching { usageStore.markSuggestViewed() }
        }
    }

    /** 每包名每小时的自动建议预算（蓝图 §7.4 P2 推理门：防同一 App 刷屏）。 */
    private fun autoBudgetAllow(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val deque = autoSuggestTimestamps[packageName] ?: return true
        synchronized(deque) {
            while (!deque.isEmpty() && now - deque.first() > AUTO_SUGGEST_WINDOW_MS) deque.removeFirst()
            return deque.size < AUTO_SUGGEST_MAX_PER_HOUR
        }
    }

    private fun autoBudgetRecord(packageName: String) {
        val deque = autoSuggestTimestamps.getOrPut(packageName) { ArrayDeque() }
        synchronized(deque) { deque.addLast(System.currentTimeMillis()) }
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
        val shouldRestore = _state.value.let { it.active && it.card == null }
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
        // P2 终审建议 1：恢复成功即种子化引擎去重签名——进程重启后同一静止屏幕
        // 不会再消耗一次预算重做结果相同的自动分析。
        if (shouldRestore) engine?.onAnalysisSucceeded(payload.screenSignature)
    }

    fun pause() {
        analysisGeneration.incrementAndGet()
        _state.update {
            it.copy(
                paused = true,
                analyzing = false,
                requestedAction = "",
                statusText = context.getString(R.string.live_master_paused),
                streamingText = null,
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

    /** 展开即视为"查看"：新鲜自动结果记指标（P2 终审 #3 口径移到这里，跨重挂仍按结果时间戳去重）。 */
    fun setBubbleExpanded(expanded: Boolean) {
        val s = _state.value
        if (expanded && s.card != null && s.lastUpdatedAtMillis > bubbleLastSeenMillis) {
            if (s.lastResultAuto) markSuggestViewed(s.lastUpdatedAtMillis)
            bubbleLastSeenMillis = s.lastUpdatedAtMillis
        }
        _bubbleExpanded.value = expanded
    }

    fun stop() {
        analysisGeneration.incrementAndGet()
        loopJob?.cancel()
        loopJob = null
        eventJob?.cancel()
        eventJob = null
        bubbleWatchJob?.cancel()
        bubbleWatchJob = null
        activeRunId?.let(agentRunner::cancel)
        activeRunId = null
        analysisJob?.cancel()
        analysisJob = null
        bubble.hide()
        engine = null
        pendingSnapshot = null
        screenDirty = true
        focusInstruction = ""
        _bubbleExpanded.value = false
        bubbleLastSeenMillis = 0L
        _state.value = LiveModeUiState(
            statusText = context.getString(R.string.live_empty_not_started),
        )
        // 闭环（Phase 3 复审 P1）：stop 即功能关闭——回写设置源，设置页主开关与
        // 伴随页状态不再漂移；气泡长按退出也走这里，两条停止路径都同步。
        appScope.launch(Dispatchers.IO) {
            settingsStore.update { settings ->
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        liveMode = settings.agentRuntime.liveMode.copy(enabled = false),
                    ),
                )
            }
        }
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
                        streamingText = null,
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

            // 每 tick 用最新设置与熔断集刷新填入白名单（Phase 9 检查 #1：
            // 签名去重使快照分支不重算，静态屏幕下设置变更/熔断后 UI 标签会滞留）。
            val freshFillAllowed = pendingSnapshot?.let {
                LiveScenes.classify(it.packageName, liveSetting.sceneOverrides) == LiveScene.CHAT &&
                    it.packageName !in fillDenylist
            } ?: false
            if (_state.value.fillAllowed != freshFillAllowed) {
                _state.update { it.copy(fillAllowed = freshFillAllowed) }
            }

            // 有限自动建议（蓝图 §7.4 P2，三分门控）：
            // 采集门=逐 App 开启（autoSuggestPackages，默认关）；推理门=引擎防抖/去重/冷却
            //  + 每包名每小时预算；打扰门=场景有默认动作（OTHER 静默）。手动触发不受影响。
            val pending = pendingSnapshot
            if (pending != null && pending.packageName in liveSetting.autoSuggestPackages) {
                val scene = LiveScenes.classify(pending.packageName, liveSetting.sceneOverrides)
                // 自动建议不得顶掉用户在飞的手动分析（P2 终审 #1）。
                if (!_state.value.analyzing &&
                    LiveScenes.defaultActionLabel(scene) != null &&
                    autoBudgetAllow(pending.packageName) &&
                    engine.decide(System.currentTimeMillis()) == LiveEngine.Decision.Analyze
                ) {
                    autoBudgetRecord(pending.packageName)
                    analyzeSnapshot(pending, force = false)
                }
            }

            // 手动语义打底（蓝图 v3 §7.2 P0-7）：无自动建议的 App 里屏幕事件只驱动快照与状态。
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

        // 场景默认动作：显式指令优先，其次场景画像（含用户自定义映射），最后通用屏幕分析
        val sceneDefault = LiveScenes.defaultActionLabel(
            LiveScenes.classify(snapshot.packageName, settings.agentRuntime.liveMode.sceneOverrides)
        )
        val actionLabel = if (focusInstruction.isBlank()) {
            sceneDefault ?: DEFAULT_ACTION_LABEL
        } else {
            liveActionLabel(focusInstruction)
        }

        val generation = analysisGeneration.incrementAndGet()
        engine.onAnalysisStarted(now)
        lastStreamEmitAt = 0L
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
                        streamingText = null,
                    )
                }
                // 一次分析 = 一个 run（蓝图 v3 §7.1 执行顺序链）：run 只包推理，
                // 采集/门控在本域层。截图采集含同次观察校验（蓝图 §7.3 P1-8）。
                val liveSetting = settings.agentRuntime.liveMode
                val screenshotUri = if (liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE) {
                    captureVerifiedScreenshot(snapshot)
                } else {
                    null
                }
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
                    analysisMode = liveSetting.analysisMode.name.lowercase(),
                    screenshotUri = screenshotUri,
                )
                try {
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
                                    streamingText = null,
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
                                    streamingText = null,
                                    lastResultAuto = !force,
                                )
                            }
                            // 结果通知（P1-6/P2-2）：仅气泡显示中（用户在外 App）才发。
                            // 指标口径（P2 终审 #3）：建议数=成功产出结果的次数（用户真实被打扰），
                            // 失败不计；触发成本由 autoBudget 在触发时记账。
                            if (!force) {
                                runCatching { withContext(NonCancellable) { usageStore.accumulateSuggest() } }
                            }
                            notifyAnalysisDone(actionLabel, artifact.card.watching, auto = !force)
                        }
                        }
                    }
                } finally {
                        // 截图即用即删（蓝图 v3 §7.2 P0-6 / §7.3 P1-8）：只删本次 run 的文件。
                        screenshotter.cleanup(screenshotUri)
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
                            // 失败/超时保留半截流式文本（蓝图 Phase 6），下次分析开始时才清空。
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

    /** P1-8 图树同次观察（蓝图 v3 §7.3）：Amber 前台不拍（只会拍到自己）；
     *  活动包名与快照不一致 = 窗口已切换，图树不匹配，拒绝带图降级纯文字。
     *  候选窗口有 id 时按窗口截（API 34+，气泡不混入），否则整屏回退。 */
    private suspend fun captureVerifiedScreenshot(snapshot: LiveScreenSnapshot): String? {
        val service = AmberAccessibilityService.getActiveService() ?: return null
        val activePackage = service.activePackageName()
        if (activePackage == context.packageName) return null
        if (activePackage != null && activePackage != snapshot.packageName) return null
        return screenshotter.captureToFileUri(service, snapshot.windowId)
    }

    /**
     * 屏幕写入仲裁（蓝图 §7.2 P0-5 + §7.3 P1-2 白名单填入）。决策核在
     *  [LiveFillPolicy]（纯逻辑，JVM 锁定）；这里做平台探测与执行：
     * 写入前现抓窗口身份与卡片来源比对（封死 runLoop tick 的同包切会话盲窗），
     * 目标框已有非草稿文本 → NEEDS_CONFIRM（5s 内二次点击 = 明确覆盖选择）；
     * 写入后回读不符 → 熔断该包名（进程级）并降级复制。冲突一律拒绝，不排队。
     */
    fun fillCurrentDraft(): LiveFillResult {
        val state = _state.value
        val card = state.card ?: return LiveFillResult.NO_DRAFT
        val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: card.watching.takeIf { it.isNotBlank() }
            ?: return LiveFillResult.NO_DRAFT
        val targetPackage = state.currentPackage
        val service = AmberAccessibilityService.getActiveService()
        if (service == null) {
            copyDraftToClipboard(draft)
            return LiveFillResult.COPIED
        }

        // 同次观察重校验（Phase 7 检查 #1）：cardStale 依赖 runLoop tick（≤1.5s），
        // 同包切会话的盲窗内 stale 仍为 false——写入前现抓窗口身份兜底比对。
        val liveSetting = settingsStore.settingsFlow.value.agentRuntime.liveMode
        val fresh = runCatching {
            service.captureLiveUiSnapshot(
                ownPackageName = context.packageName,
                maxNodes = liveSetting.maxNodes.coerceIn(40, 260),
            )
        }.getOrNull()
        val existing = service.readTextInPackage(targetPackage)
        val confirmed = pendingFillConfirm?.let { (pkg, hash, at) ->
            pkg == targetPackage && hash == draft.hashCode() &&
                System.currentTimeMillis() - at <= FILL_CONFIRM_WINDOW_MS
        } == true
        val decision = LiveFillPolicy.decide(
            cardStale = state.cardStale,
            scene = LiveScenes.classify(targetPackage, liveSetting.sceneOverrides),
            denied = targetPackage in fillDenylist,
            targetPresent = existing != null,
            contextMatches = fresh != null &&
                fresh.packageName == targetPackage && fresh.title == state.currentTitle,
            existingText = existing,
            draft = draft,
            confirmed = confirmed,
        )
        return when (decision) {
            LiveFillPolicy.Decision.COPY_STALE -> {
                copyDraftToClipboard(draft)
                LiveFillResult.REJECTED_STALE
            }
            LiveFillPolicy.Decision.COPY -> {
                copyDraftToClipboard(draft)
                LiveFillResult.COPIED
            }
            LiveFillPolicy.Decision.CONFIRM -> {
                pendingFillConfirm = Triple(targetPackage, draft.hashCode(), System.currentTimeMillis())
                LiveFillResult.NEEDS_CONFIRM
            }
            LiveFillPolicy.Decision.FILL -> {
                pendingFillConfirm = null
                when (service.setTextInPackageVerified(targetPackage, draft)) {
                    LiveFillOutcome.SUCCESS -> LiveFillResult.FILLED
                    LiveFillOutcome.MISMATCH -> {
                        // 写入未被接受/被并发覆盖：熔断该包名，不自动重试。
                        fillDenylist += targetPackage
                        copyDraftToClipboard(draft)
                        LiveFillResult.COPIED
                    }
                    LiveFillOutcome.NOT_FOUND, LiveFillOutcome.UNKNOWN -> {
                        copyDraftToClipboard(draft)
                        LiveFillResult.COPIED
                    }
                }
            }
        }
    }

    private fun copyDraftToClipboard(draft: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("amber-live-draft", draft))
    }

    /** 整卡复制（页面"复制"chip）：文案与"发到聊天"同格式，走剪贴板。 */
    fun copyCurrentCardToClipboard(): Boolean {
        val text = exportCurrentCard() ?: return false
        copyDraftToClipboard(text)
        return true
    }

    /** 保存当前卡片到历史（蓝图 §7.3 P1-3）；无可保存内容返回 false。 */
    suspend fun saveCurrentCard(): Boolean = cardStore.save(_state.value)

    /** 用户保存的卡片时间线（伴随页历史区）。 */
    val savedCards = cardStore.savedCards

    suspend fun deleteSavedCard(id: Long) = cardStore.delete(id)

    /** 撤销删除（toast Undo）：原 id 仍在（延迟删除未落库）则跳过，否则按原 createdAt 重插。 */
    suspend fun restoreSavedCard(card: LiveCardEntity) = cardStore.restore(card)

    /**
     * 结果通知（蓝图 §7.3 P1-6 / §7.4 P2-2）：仅在气泡显示中（用户在外 App）时通知；
     * 伴随页在前台（Amber 前台时气泡隐藏）不打扰。点击打开伴随页。
     * 通知默认 VISIBILITY_PRIVATE（NotificationUtil 默认），锁屏不暴露第三方摘要。
     * auto=true 时标题区分"伴随建议"（P2 自动建议）与手动"伴随完成"。
     */
    private fun notifyAnalysisDone(actionLabel: String, watching: String, auto: Boolean = false) {
        if (!bubble.isShowing) return
        val intent = Intent(context, app.amber.agent.RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(app.amber.agent.RouteActivity.EXTRA_OPEN_LIVE_COMPANION, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            LIVE_RESULT_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.sendNotification(
            channelId = app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = LIVE_RESULT_NOTIFICATION_ID,
        ) {
            title = context.getString(
                if (auto) R.string.live_notification_suggest_title else R.string.live_notification_result_title,
                localizedActionLabel(actionLabel),
            )
            content = watching.take(120)
            smallIcon = R.drawable.amberagent_live_status_icon
            autoCancel = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = pending
        }
    }

    /** 每个 runLoop tick 调一次：根据状态决定气泡显隐。仅主线程。 */
    private fun syncBubble(liveSetting: LiveModeSetting) {
        val service = AmberAccessibilityService.getActiveService()
        if (service == null ||
            !liveSetting.enabled ||
            !liveSetting.bubbleEnabled ||
            !_state.value.active ||
            service.activePackageName() == context.packageName ||
            // 任务气泡与伴随气泡共用窗口位置，agent 任务运行中优先展示任务
            taskBubble.visible.value
        ) {
            bubble.hide()
            return
        }
        bubble.show(service) {
            AmberAgentTheme {
                val uiState by state.collectAsState()
                val isExpanded by bubbleExpanded.collectAsState()
                LiveBubbleContent(
                    state = uiState,
                    expanded = isExpanded,
                    lastSeenMillis = bubbleLastSeenMillis,
                    onExpandedChange = ::setBubbleExpanded,
                    onFillDraft = ::fillCurrentDraft,
                    onRefresh = ::refreshNow,
                    onStop = ::stop,
                    onDrag = bubble::moveBy,
                    onDragEnd = bubble::snapToEdge,
                    onSizeChanged = bubble::requestReclamp,
                    anchorEndProvider = bubble::isAnchoredEnd,
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

        /** 流式预览节流与长度上限。 */
        private const val STREAM_EMIT_INTERVAL_MS = 80L
        private const val STREAM_TEXT_LIMIT = 500

        /** 伴随结果通知的固定 id（新结果覆盖旧通知）。 */
        private const val LIVE_RESULT_NOTIFICATION_ID = 4207

        /** 自动建议预算：每包名每小时最多 4 次（蓝图 §7.4 P2 推理门）。 */
        private const val AUTO_SUGGEST_WINDOW_MS = 3_600_000L
        private const val AUTO_SUGGEST_MAX_PER_HOUR = 4
    }
}

/** FILLED=已写入目标框；COPIED=已复制剪贴板；NO_DRAFT=无草稿；
 *  NEEDS_CONFIRM=目标已有文本待用户明确覆盖；REJECTED_STALE=卡片脱离现场已改复制。 */
enum class LiveFillResult { FILLED, COPIED, NO_DRAFT, NEEDS_CONFIRM, REJECTED_STALE }
