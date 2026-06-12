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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.ai.provider.ProviderManager
import app.amber.agent.AppScope
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.live.bubble.LiveBubbleContent
import app.amber.feature.live.bubble.LiveBubbleWindow
import app.amber.feature.ui.theme.AmberAgentTheme

class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val appScope: AppScope,
) {
    private val _state = MutableStateFlow(LiveModeUiState())
    val state: StateFlow<LiveModeUiState> = _state.asStateFlow()

    private val analyzer = LiveAnalyzer(providerManager)
    private val screenshotter = LiveScreenshotter(context)
    private val bubble = LiveBubbleWindow()

    private var loopJob: Job? = null
    private var eventJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisGeneration = 0L
    private var engine: LiveEngine? = null
    private var pendingSnapshot: LiveScreenSnapshot? = null
    private var focusInstruction: String = ""

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
        _state.value = LiveModeUiState(active = true, statusText = "伴随已开启，正在检查权限")
        eventJob = appScope.launch {
            AmberAccessibilityService.screenEvents.collect { event ->
                if (event.packageName != context.packageName) screenDirty = true
            }
        }
        loopJob = appScope.launch(Dispatchers.Main.immediate) { runLoop() }
    }

    fun pause() {
        _state.update {
            it.copy(
                paused = true,
                analyzing = false,
                statusText = "伴随已暂停",
            )
        }
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
                statusText = "正在伴随",
            )
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        eventJob?.cancel()
        eventJob = null
        analysisJob?.cancel()
        analysisJob = null
        bubble.hide()
        engine = null
        pendingSnapshot = null
        screenDirty = true
        focusInstruction = ""
        _state.value = LiveModeUiState()
    }

    fun refreshNow() {
        if (_state.value.paused) {
            _state.update { it.copy(statusText = "已暂停") }
            return
        }
        val snapshot = pendingSnapshot
        if (snapshot == null) {
            _state.update { it.copy(statusText = "还没有读到可分析的屏幕") }
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
            appendLine("请基于这段 Live 伴随观察继续帮我分析。")
            appendLine()
            appendLine("当前应用：${current.currentAppLabel.ifBlank { current.currentPackage }}")
            if (current.currentTitle.isNotBlank()) {
                appendLine("当前页面：${current.currentTitle}")
            }
            if (current.currentFocus.isNotBlank()) {
                appendLine("我的关注点：${current.currentFocus}")
            }
            if (current.completedAction.isNotBlank()) {
                appendLine("本次结果类型：${current.completedAction}")
            }
            appendLine()
            appendLine("正在看什么：${card.watching}")
            if (card.keyPoints.isNotEmpty()) {
                appendLine("我觉得重点是：")
                card.keyPoints.forEach { appendLine("- $it") }
            }
            if (card.suggestions.isNotEmpty()) {
                appendLine("可以怎么做：")
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
                _state.update {
                    it.copy(
                        active = false,
                        paused = false,
                        analyzing = false,
                        statusText = "Live 模式已关闭",
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
                        statusText = "请先配置聊天模型",
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
                            "等待无障碍服务连接"
                        } else {
                            "请开启 AmberAgent 无障碍服务"
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
                            analyzing = false, statusText = "未识别到另一侧内容",
                        )
                    }
                    delay(tickInterval)
                    continue
                }
                val now = System.currentTimeMillis()
                if (engine.onScreenSignature(snapshot.stableHash, now)) {
                    pendingSnapshot = snapshot
                    _state.update {
                        it.copy(
                            active = true, needsAccessibility = false, noModelConfigured = false,
                            currentPackage = snapshot.packageName,
                            currentAppLabel = snapshot.appLabel,
                            currentTitle = snapshot.title,
                            lastSnapshotHash = snapshot.stableHash,
                            statusText = "正在伴随 ${snapshot.appLabel.ifBlank { snapshot.packageName }}",
                        )
                    }
                }
            }

            // 场景静默：OTHER 且用户没给焦点指令 → 不自动分析
            val snapshot = pendingSnapshot
            if (snapshot != null && liveSetting.autoRefresh) {
                val scene = LiveScenes.classify(snapshot.packageName)
                val silent = scene == LiveScene.OTHER && focusInstruction.isBlank()
                if (silent) {
                    _state.update {
                        if (it.analyzing || it.card != null) it
                        else it.copy(statusText = "在 ${snapshot.appLabel.ifBlank { snapshot.packageName }} 待命，点击分析或下达指令")
                    }
                } else if (engine.decide(System.currentTimeMillis()) == LiveEngine.Decision.Analyze) {
                    analyzeSnapshot(snapshot, force = false)
                }
            }
            delay(tickInterval)
        }
    }

    private fun analyzeSnapshot(snapshot: LiveScreenSnapshot, force: Boolean) {
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        val liveSetting = settings.agentRuntime.liveMode
        when (val d = engine.decide(now, force)) {
            is LiveEngine.Decision.Wait -> {
                if (d.reason == "backoff") {
                    _state.update {
                        it.copy(
                            statusText = "模型服务繁忙，稍后自动重试",
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
            _state.update { it.copy(noModelConfigured = true, statusText = "请先配置聊天模型") }
            return
        }

        // 场景默认动作：显式指令优先，其次场景画像，最后通用屏幕分析
        val sceneDefault = LiveScenes.defaultActionLabel(LiveScenes.classify(snapshot.packageName))
        val actionLabel = if (focusInstruction.isBlank()) {
            sceneDefault ?: DEFAULT_ACTION_LABEL
        } else {
            liveActionLabel(focusInstruction)
        }

        val generation = ++analysisGeneration
        engine.onAnalysisStarted(now)
        analysisJob?.cancel()
        analysisJob = appScope.launch(Dispatchers.IO) {
            try {
                _state.update {
                    it.copy(
                        analyzing = true,
                        requestedAction = if (actionLabel == DEFAULT_ACTION_LABEL) it.requestedAction else actionLabel,
                        completedAction = "",
                        statusText = ongoingStatus(actionLabel),
                        error = null,
                        nextAnalysisAfterMillis = 0L,
                    )
                }
                val screenshotUri = if (liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE) {
                    AmberAccessibilityService.getActiveService()?.let { svc ->
                        // Amber 自己全屏在前台时截屏只会拍到自己，喂给模型反而污染分析 → 跳过
                        val activePackage = svc.activePackageName()
                        if (activePackage == context.packageName) null
                        else screenshotter.captureToFileUri(svc)
                    }
                } else null
                val outcome = analyzer.analyze(
                    settings = settings,
                    model = model,
                    snapshot = snapshot,
                    focus = focusInstruction,
                    actionLabel = actionLabel,
                    mode = liveSetting.analysisMode,
                    screenshotUri = screenshotUri,
                )
                withContext(Dispatchers.Main.immediate) {
                    if (generation == analysisGeneration) {
                        engine.onAnalysisSucceeded(snapshot.stableHash)
                        _state.update {
                            it.copy(
                                analyzing = false,
                                card = outcome.card,
                                currentPackage = snapshot.packageName,
                                currentAppLabel = snapshot.appLabel,
                                currentTitle = snapshot.title,
                                requestedAction = "",
                                completedAction = actionLabel,
                                statusText = outcome.degradedReason ?: doneStatus(actionLabel),
                                error = null,
                                lastUpdatedAtMillis = System.currentTimeMillis(),
                                nextAnalysisAfterMillis = 0L,
                            )
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Live analysis failed", error)
                withContext(Dispatchers.Main.immediate) {
                    val failure = LiveFailure.from(error)
                    if (failure.retryable) engine.onRetryableFailure(System.currentTimeMillis())
                    _state.update {
                        it.copy(
                            analyzing = false,
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
        if (actionLabel == DEFAULT_ACTION_LABEL) "正在分析屏幕" else "正在$actionLabel"

    private fun doneStatus(actionLabel: String): String =
        if (actionLabel == DEFAULT_ACTION_LABEL) "正在伴随，已更新" else "${actionLabel}结果已更新"

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
            fun from(error: Throwable): LiveFailure {
                val raw = (error.message ?: error.toString()).trim()
                val lower = raw.lowercase()
                return when {
                    "503" in raw ||
                        "service_unavailable" in lower ||
                        "too busy" in lower -> LiveFailure(
                            statusText = "模型服务繁忙",
                            message = "模型服务返回 503，当前太忙。伴随仍在读取屏幕，但会暂停自动分析约 30 秒；你也可以稍后再试或切换模型服务。",
                            retryable = true,
                        )

                    "timeout" in lower || "timed out" in lower -> LiveFailure(
                        statusText = "模型响应超时",
                        message = "模型请求超时。伴随仍在读取屏幕，稍后会自动重试；如果频繁出现，可以换一个更稳定的模型。",
                        retryable = true,
                    )

                    else -> LiveFailure(
                        statusText = "分析失败",
                        message = raw.ifBlank { "模型分析失败，请稍后再试。" }.take(220),
                        retryable = false,
                    )
                }
            }
        }
    }

    /** 只填不发：草稿写进对方输入框；失败降级剪贴板。 */
    fun fillCurrentDraft(): LiveFillResult {
        val card = _state.value.card ?: return LiveFillResult.NO_DRAFT
        val draft = card.suggestions.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: card.watching.takeIf { it.isNotBlank() }
            ?: return LiveFillResult.NO_DRAFT
        val service = AmberAccessibilityService.getActiveService()
        if (service != null) {
            val targetPackage = _state.value.currentPackage
            if (targetPackage.isNotBlank() && service.setTextInPackage(targetPackage, draft)) {
                return LiveFillResult.FILLED
            }
            if (service.setFocusedText(draft)) return LiveFillResult.FILLED
        }
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
    }
}

enum class LiveFillResult { FILLED, COPIED, NO_DRAFT }
