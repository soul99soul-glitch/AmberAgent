package app.amber.feature.live

import android.content.Context
import app.amber.ai.provider.ProviderCatalog
import app.amber.agent.R
import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventPayload
import app.amber.core.agent.runtime.AgentHandler
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.settings.prefs.SettingsAggregator
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Live 伴随的 run 契约（蓝图 v3 §7.2 P0-2）：一次"通过门控、绑定确定屏幕上下文"
 * 的分析请求 = 一个 run；run 只包推理，采集与门控在 LiveModeManager 域层完成。
 * P1-A 起支持截图分析：analysisMode 由域层读取设置传入，截图在域层采集并校验。
 */
@Serializable
data class LiveTurnInput(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val contentText: String,
    val uiTree: String,
    val screenSignature: String,
    val focus: String,
    val actionLabel: String,
    val localeTag: String,
    val capturedAtMillis: Long,
    /** "conservative"（仅文字）/ "aggressive"（截图+文字）；与 LiveAnalysisMode 的 wire 名一致。 */
    val analysisMode: String = "conservative",
    /** 截图 file:// URI（域层采集；仅 aggressive 且校验通过时非空）。 */
    val screenshotUri: String? = null,
) : AgentInput

@Serializable
data class LiveTurnArtifact(
    val card: LiveModeCard,
    val degradedReason: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val modelId: String = "",
) : AgentArtifact

/** 落库的 Final payload（codec 注册进 AgentRuntimeModule 的 codec map 后 durable）。 */
sealed interface LiveEventPayload {

    @Serializable
    data class AnalysisCompleted(
        val screenSignature: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val actionLabel: String,
        val watching: String,
        val keyPoints: List<String>,
        val suggestions: List<String>,
        val promptTokens: Int,
        val completionTokens: Int,
        val cachedTokens: Int,
        val modelId: String,
    ) : LiveEventPayload, AgentEventPayload.Final {
        companion object {
            const val TYPE = "LiveAnalysisCompleted"
        }
    }
}

object LiveTurnDescriptor {
    val ID = AgentDescriptorId("live_turn")

    val value = AgentDescriptor(
        id = ID,
        version = "1.0.0",
        displayName = "Live Companion",
        capabilities = setOf(AgentCapability.BACKGROUND),
    )
}

class LiveTurnAgent(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val streamSink: LiveModeManager,
    private val recallStore: app.amber.core.memory.recall.MemoryRecallStore,
) : Agent<LiveTurnInput, LiveTurnArtifact> {
    override val descriptor: AgentDescriptor = LiveTurnDescriptor.value

    override val handler = AgentHandler<LiveTurnInput, LiveTurnArtifact> { input, scope ->
        val analyzer = LiveAnalyzer(providerCatalog, context)
        val settings = settingsStore.settingsFlow.value
        val model = analyzer.resolveModel(settings)
            ?: error(context.getString(R.string.live_model_required_title))
        val snapshot = LiveScreenSnapshot(
            packageName = input.packageName,
            appLabel = input.appLabel,
            title = input.title,
            uiTree = input.uiTree,
            visibleText = input.contentText,
            contentText = input.contentText,
            nodeCount = 0,
            capturedAtMillis = input.capturedAtMillis,
        )
        // 记忆融合（蓝图 §7.4 P2 三选一）：纯 DB 召回前 3 条作背景段喂入；
        // 召回失败不阻断分析。有意用 recall() 而非 buildPrompt()——后者会 touchMemories
        // 更新 lastUsedAt，伴随的高频分析不应污染记忆新鲜度排序。
        val recallLines = runCatching {
            recallStore.recall(
                settings = settings,
                messages = listOf(app.amber.ai.ui.UIMessage.user(input.contentText.take(500))),
            ).take(3).map { "- ${it.content.take(120)}" }
        }.getOrDefault(emptyList())
        val outcome = analyzer.analyzeStream(
            settings = settings,
            model = model,
            snapshot = snapshot,
            focus = input.focus,
            actionLabel = input.actionLabel,
            mode = runCatching { LiveAnalysisMode.valueOf(input.analysisMode.uppercase()) }
                .getOrDefault(LiveAnalysisMode.CONSERVATIVE),
            screenshotUri = input.screenshotUri,
            locale = Locale.forLanguageTag(input.localeTag),
            recallLines = recallLines,
            onDelta = streamSink::onLiveStreamDelta,
        )
        val usage = outcome.usage
        scope.events.commit(
            LiveEventPayload.AnalysisCompleted(
                screenSignature = input.screenSignature,
                packageName = input.packageName,
                appLabel = input.appLabel,
                title = input.title,
                actionLabel = input.actionLabel,
                watching = outcome.card.watching,
                keyPoints = outcome.card.keyPoints,
                suggestions = outcome.card.suggestions,
                promptTokens = usage?.promptTokens ?: 0,
                completionTokens = usage?.completionTokens ?: 0,
                cachedTokens = usage?.cachedTokens ?: 0,
                modelId = model.modelId,
            ),
        )
        LiveTurnArtifact(
            card = outcome.card,
            degradedReason = outcome.degradedReason,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0,
            cachedTokens = usage?.cachedTokens ?: 0,
            modelId = model.modelId,
        )
    }
}
