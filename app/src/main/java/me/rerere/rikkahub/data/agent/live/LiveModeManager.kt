package me.rerere.rikkahub.data.agent.live

import android.content.Context
import android.util.Log
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
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
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.automation.AmberAccessibilityService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

class LiveModeManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val appScope: AppScope,
) {
    private val _state = MutableStateFlow(LiveModeUiState())
    val state: StateFlow<LiveModeUiState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisGeneration = 0L
    private var pendingSnapshot: LiveScreenSnapshot? = null
    private var pendingChangedAtMillis: Long = 0L
    private var lastAnalyzedHash: String? = null
    private var lastAnalysisAtMillis: Long = 0L
    private var analysisBackoffUntilMillis: Long = 0L
    private var focusInstruction: String = ""

    fun start() {
        if (observeJob?.isActive == true) {
            resume()
            return
        }
        _state.value = LiveModeUiState(
            active = true,
            statusText = "伴随已开启，正在检查权限",
        )
        observeJob = appScope.launch(Dispatchers.Main.immediate) {
            observeLoop()
        }
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
        if (observeJob?.isActive != true) {
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
        observeJob?.cancel()
        observeJob = null
        analysisJob?.cancel()
        analysisJob = null
        pendingSnapshot = null
        analysisBackoffUntilMillis = 0L
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

    private suspend fun observeLoop() {
        while (true) {
            val settings = settingsStore.settingsFlow.value
            val liveSetting = settings.agentRuntime.liveMode
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
            val model = settings.getCurrentChatModel()
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

            val snapshot = service.captureLiveUiSnapshot(
                ownPackageName = context.packageName,
                maxNodes = liveSetting.maxNodes.coerceIn(40, 260),
            )
            if (snapshot == null) {
                _state.update {
                    it.copy(
                        needsAccessibility = false,
                        noModelConfigured = false,
                        analyzing = false,
                        statusText = "未识别到另一侧内容",
                    )
                }
                delay(liveSetting.refreshIntervalMs.coerceIn(1_000L, 5_000L))
                continue
            }

            val now = System.currentTimeMillis()
            if (snapshot.stableHash != pendingSnapshot?.stableHash) {
                pendingSnapshot = snapshot
                pendingChangedAtMillis = now
                _state.update {
                    val backingOff = now < analysisBackoffUntilMillis
                    it.copy(
                        active = true,
                        needsAccessibility = false,
                        noModelConfigured = false,
                        currentPackage = snapshot.packageName,
                        currentAppLabel = snapshot.appLabel,
                        currentTitle = snapshot.title,
                        lastSnapshotHash = snapshot.stableHash,
                        statusText = if (backingOff) {
                            "模型服务繁忙，稍后自动重试"
                        } else if (it.requestedAction.isNotBlank()) {
                            ongoingStatus(it.requestedAction)
                        } else {
                            "正在伴随 ${snapshot.appLabel.ifBlank { snapshot.packageName }}"
                        },
                        error = if (backingOff) it.error else null,
                        nextAnalysisAfterMillis = if (backingOff) analysisBackoffUntilMillis else 0L,
                    )
                }
            }

            if (now < analysisBackoffUntilMillis) {
                _state.update {
                    it.copy(
                        statusText = "模型服务繁忙，稍后自动重试",
                        nextAnalysisAfterMillis = analysisBackoffUntilMillis,
                    )
                }
                delay(liveSetting.refreshIntervalMs.coerceIn(1_000L, 5_000L))
                continue
            }

            if (liveSetting.autoRefresh && LiveUiTreeProcessor.shouldAnalyze(
                    previousHash = lastAnalyzedHash,
                    nextHash = pendingSnapshot?.stableHash,
                    nowMillis = now,
                    changedAtMillis = pendingChangedAtMillis,
                    lastAnalysisAtMillis = lastAnalysisAtMillis,
                    stableDelayMs = liveSetting.stableDelayMs.coerceIn(500L, 5_000L),
                    minAnalysisIntervalMs = liveSetting.minAnalysisIntervalMs.coerceIn(5_000L, 30_000L),
                )
            ) {
                pendingSnapshot?.let { analyzeSnapshot(it, force = false) }
            }

            delay(liveSetting.refreshIntervalMs.coerceIn(1_000L, 5_000L))
        }
    }

    private fun analyzeSnapshot(snapshot: LiveScreenSnapshot, force: Boolean) {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settingsFlow.value
        val liveSetting = settings.agentRuntime.liveMode
        if (now < analysisBackoffUntilMillis) {
            _state.update {
                it.copy(
                    statusText = if (it.requestedAction.isNotBlank()) {
                        "${it.requestedAction}排队中"
                    } else {
                        "模型服务繁忙，稍后自动重试"
                    },
                    nextAnalysisAfterMillis = analysisBackoffUntilMillis,
                )
            }
            return
        }
        if (!force && now - lastAnalysisAtMillis < liveSetting.minAnalysisIntervalMs.coerceIn(5_000L, 30_000L)) {
            return
        }
        val model = settings.getCurrentChatModel()
        if (model == null) {
            _state.update { it.copy(noModelConfigured = true, statusText = "请先配置聊天模型") }
            return
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            _state.update { it.copy(noModelConfigured = true, statusText = "当前模型没有可用服务") }
            return
        }

        val generation = ++analysisGeneration
        val actionLabel = liveActionLabel(focusInstruction)
        lastAnalysisAtMillis = now
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
                val providerImpl = providerManager.getProviderByType(provider)
                val result = providerImpl.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(LivePrompt.system),
                        UIMessage.user(LivePrompt.user(snapshot, focusInstruction, actionLabel)),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.25f,
                        topP = 0.8f,
                        maxTokens = 420,
                        tools = emptyList(),
                        reasoningLevel = ReasoningLevel.OFF,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    )
                )
                val text = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
                val card = LivePrompt.parseCard(text, actionLabel)
                withContext(Dispatchers.Main.immediate) {
                    if (generation == analysisGeneration) {
                        lastAnalyzedHash = snapshot.stableHash
                        analysisBackoffUntilMillis = 0L
                        _state.update {
                            it.copy(
                                analyzing = false,
                                card = card,
                                currentPackage = snapshot.packageName,
                                currentAppLabel = snapshot.appLabel,
                                currentTitle = snapshot.title,
                                requestedAction = "",
                                completedAction = actionLabel,
                                statusText = doneStatus(actionLabel),
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
                val failure = LiveFailure.from(error)
                if (failure.retryable) {
                    analysisBackoffUntilMillis = System.currentTimeMillis() + MODEL_BUSY_BACKOFF_MS
                }
                _state.update {
                    it.copy(
                        analyzing = false,
                        statusText = failure.statusText,
                        error = failure.message,
                        completedAction = "",
                        nextAnalysisAfterMillis = if (failure.retryable) analysisBackoffUntilMillis else 0L,
                    )
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

    private object LivePrompt {
        const val system = """
你是 AmberAgent 的 Live 伴随模式。你正在根据 Android 无障碍 UI 树做只读现场分析。

规则：
- 只基于提供的屏幕正文和少量 UI 树判断，不声称看到了 UI 树之外的视觉细节。
- 忽略状态栏、导航栏、输入法、分屏线、窗口框架、Tab、按钮、可点击状态、bounds、className。
- 不要分析“Canvas Window”“分屏分割线”“多窗口界面”等系统框架，除非屏幕正文明确与它相关。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合 360dp 宽的分屏侧栏阅读。
- 如果信息不足，直接说“不确定”，不要用泛泛建议填充。
"""

        fun user(snapshot: LiveScreenSnapshot, focus: String, actionLabel: String): String = buildString {
            appendLine("当前应用：${snapshot.appLabel.ifBlank { snapshot.packageName }}")
            appendLine("包名：${snapshot.packageName}")
            if (snapshot.title.isNotBlank()) appendLine("窗口标题：${snapshot.title}")
            if (snapshot.windowDebugLabel.isNotBlank()) appendLine("窗口候选：${snapshot.windowDebugLabel}")
            if (focus.isNotBlank()) appendLine("用户关注点：$focus")
            appendLine("任务：$actionLabel")
            appendLine("输出格式：")
            appendLine(actionContract(actionLabel))
            appendLine("屏幕正文：")
            appendLine(snapshot.contentText.ifBlank { snapshot.visibleText }.take(4_000))
            appendLine()
            appendLine("补充 UI 树，仅用于消歧，不要复述控件框架：")
            appendLine(snapshot.uiTree.take(4_000))
        }

        fun parseCard(text: String, actionLabel: String): LiveModeCard {
            val conclusion = firstNonBlankSection(text, "结论", "总结", "回复", "正在看什么")
                .ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
                .let { LiveUiTreeProcessor.cleanAnalysisItem(it).orEmpty() }
                .ifBlank { "不确定" }
            val keyPoints = when (actionLabel) {
                "找重点" -> sectionItems(text, 3, "重点", "我觉得重点是", "关键信息")
                "总结" -> sectionItems(text, 3, "事实", "重点", "关键信息")
                "查风险" -> sectionItems(text, 3, "风险", "风险点", "重点")
                "写回复" -> sectionItems(text, 1, "语气", "依据", "重点")
                else -> sectionItems(text, 3, "重点", "我觉得重点是", "判断依据")
            }
            val suggestions = when (actionLabel) {
                "找重点" -> emptyList()
                "总结" -> emptyList()
                "找下一步" -> sectionItems(text, 3, "下一步", "行动", "建议", "可以怎么做")
                "查风险" -> emptyList()
                "写回复" -> sectionItems(text, 1, "回复", "回复草稿", "建议回复").ifEmpty { listOf(conclusion) }
                else -> sectionItems(text, 2, "下一步", "建议", "可以怎么做")
            }
            return LiveModeCard(
                watching = conclusion,
                keyPoints = keyPoints,
                suggestions = suggestions,
                followUps = emptyList(),
                rawText = text,
            )
        }

        private fun actionContract(actionLabel: String): String = when (actionLabel) {
            "找重点" -> "结论：一句话\n重点：\n- 最重要的信息 1\n- 最重要的信息 2\n- 最重要的信息 3"
            "总结" -> "总结：一句话\n事实：\n- 事实 1\n- 事实 2\n- 事实 3"
            "找下一步" -> "结论：一句话\n下一步：\n- 建议 1\n- 建议 2\n- 建议 3"
            "查风险" -> "结论：一句话\n风险：\n- 风险 1\n- 风险 2\n- 风险 3\n如果没有明确风险，只输出：结论：暂未发现明确风险"
            "写回复" -> "回复：一条可直接发送的短回复\n语气：一句话说明"
            else -> "结论：一句话\n重点：\n- 要点 1\n- 要点 2\n下一步：\n- 建议 1"
        }

        private fun firstNonBlankSection(text: String, vararg names: String): String =
            names.firstNotNullOfOrNull { name ->
                val prefixZh = "$name："
                val prefixEn = "$name:"
                text.lineSequence()
                    .firstOrNull {
                        val trimmed = it.trimStart()
                        trimmed.startsWith(prefixZh) || trimmed.startsWith(prefixEn)
                    }
                    ?.substringAfter('：')
                    ?.substringAfter(':')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.orEmpty()

        private fun sectionItems(text: String, maxItems: Int, vararg names: String): List<String> {
            val lines = text.lines()
            val start = lines.indexOfFirst { line ->
                val normalized = line.trim().trimEnd('：', ':')
                names.any { it == normalized }
            }
            if (start < 0) return emptyList()
            val rawItems = lines.drop(start + 1)
                .takeWhile { line ->
                    val trimmed = line.trim()
                    trimmed.isBlank() ||
                        trimmed.startsWith("-") ||
                        trimmed.startsWith("•") ||
                        trimmed.startsWith("*") ||
                        !trimmed.contains("：")
                }
            return LiveUiTreeProcessor.compactAnalysisItems(rawItems, maxItems)
        }
    }

    companion object {
        private const val TAG = "LiveModeManager"
        private const val DEFAULT_ACTION_LABEL = "屏幕分析"
        private const val MODEL_BUSY_BACKOFF_MS = 30_000L
    }
}
