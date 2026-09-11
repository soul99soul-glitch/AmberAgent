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
import app.amber.ai.provider.ProviderCatalog
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.appLocale
import app.amber.feature.live.bubble.LiveBubbleContent
import app.amber.feature.live.bubble.LiveBubbleWindow
import app.amber.feature.ui.theme.AmberAgentTheme

class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val appScope: AppScope,
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
                statusText = context.getString(R.string.live_master_paused),
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
                statusText = context.getString(R.string.live_master_reading),
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
                _state.update {
                    it.copy(
                        active = false,
                        paused = false,
                        analyzing = false,
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
                        it.copy(
                            active = true, needsAccessibility = false, noModelConfigured = false,
                            currentPackage = snapshot.packageName,
                            currentAppLabel = snapshot.appLabel,
                            currentTitle = snapshot.title,
                            lastSnapshotHash = snapshot.stableHash,
                            statusText = readingStatus(snapshot.appLabel.ifBlank { snapshot.packageName }),
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
                        else it.copy(statusText = readingStatus(snapshot.appLabel.ifBlank { snapshot.packageName }))
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
                    locale = context.appLocale(),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (generation == analysisGeneration.get()) {
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
                    val failure = LiveFailure.from(
                        context = context,
                        error = error,
                        actionLabel = localizedActionLabel(actionLabel),
                    )
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
