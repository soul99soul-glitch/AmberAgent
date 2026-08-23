package app.amber.feature.live

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import kotlin.uuid.Uuid

/**
 * 伴随分析器：模型解析（伴随模型→聊天模型回退）、保守/激进双模式消息构建、
 * 调用与卡片解析。从 LiveModeManager 拆出，Manager 只编排不碰 prompt。
 */
class LiveAnalyzer(
    private val providerManager: ProviderManager,
) {
    data class Outcome(
        val card: LiveModeCard,
        val usedVision: Boolean,
        /** 非空 = 激进模式被降级的原因（提示用） */
        val degradedReason: String?,
    )

    /** 解析伴随模型：companionModelId 优先，无效/未设则跟随聊天模型。 */
    fun resolveModel(settings: Settings): Model? {
        val uuid = settings.agentRuntime.liveMode.companionModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val byId = uuid?.let { settings.findModelById(it) }?.takeIf { it.type == ModelType.CHAT }
        return byId ?: settings.getCurrentChatModel()
    }

    /**
     * @param screenshotUri 激进模式下由调用方先截好（file:// URI）；
     *        null 表示不可用（低版本/截屏失败/保守模式），自动走纯文字。
     * @throws Throwable 网络/模型错误原样抛出，由 Manager 统一映射 LiveFailure。
     */
    suspend fun analyze(
        settings: Settings,
        model: Model,
        snapshot: LiveScreenSnapshot,
        focus: String,
        actionLabel: String,
        mode: LiveAnalysisMode,
        screenshotUri: String?,
    ): Outcome {
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("当前模型没有可用服务")
        val wantVision = mode == LiveAnalysisMode.AGGRESSIVE
        val modelSupportsVision = Modality.IMAGE in model.inputModalities
        val useVision = wantVision && modelSupportsVision && screenshotUri != null
        val degradedReason = when {
            !wantVision -> null
            !modelSupportsVision -> "伴随模型不支持图片输入，已按保守模式分析"
            screenshotUri == null -> "截屏不可用，已按保守模式分析"
            else -> null
        }

        val messages = if (useVision) {
            listOf(
                UIMessage.system(LivePrompt.visionSystem),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text(LivePrompt.user(snapshot, focus, actionLabel)),
                        UIMessagePart.Image(url = screenshotUri),
                    ),
                ),
            )
        } else {
            listOf(
                UIMessage.system(LivePrompt.system),
                UIMessage.user(LivePrompt.user(snapshot, focus, actionLabel)),
            )
        }

        val providerImpl = providerManager.getProviderByType(provider)
        val result = providerImpl.generateText(
            providerSetting = provider,
            messages = messages,
            params = TextGenerationParams(
                model = model,
                temperature = 0.25f,
                topP = 0.8f,
                maxTokens = 420,
                tools = emptyList(),
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
        val text = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        return Outcome(
            card = LivePrompt.parseCard(text, actionLabel),
            usedVision = useVision,
            degradedReason = degradedReason,
        )
    }

    internal object LivePrompt {
        const val system = """
你是 AmberAgent 的 Live 伴随模式。你正在根据 Android 无障碍 UI 树做只读现场分析。

规则：
- 只基于提供的屏幕正文和少量 UI 树判断，不声称看到了 UI 树之外的视觉细节。
- 忽略状态栏、导航栏、输入法、分屏线、窗口框架、Tab、按钮、可点击状态、bounds、className。
- 不要分析"Canvas Window""分屏分割线""多窗口界面"等系统框架，除非屏幕正文明确与它相关。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合 360dp 宽的分屏侧栏阅读。
- 如果信息不足，直接说"不确定"，不要用泛泛建议填充。
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

        /** 激进模式：截图为主信号 */
        const val visionSystem = """
你是 AmberAgent 的 Live 伴随模式。你会收到一张当前手机屏幕截图，以及辅助的无障碍文字提取。

规则：
- 以截图为主要依据；无障碍文字仅用于核对文本细节（如长串号码、链接）。
- 忽略状态栏、导航栏、输入法、悬浮窗等系统框架。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合手机侧栏阅读。
- 如果信息不足，直接说"不确定"，不要用泛泛建议填充。
"""
    }
}
