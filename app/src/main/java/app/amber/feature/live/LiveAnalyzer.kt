package app.amber.feature.live

import android.content.Context
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 伴随分析器：模型解析（伴随模型→聊天模型回退）、保守/激进双模式消息构建、
 * 调用与卡片解析。从 LiveModeManager 拆出，Manager 只编排不碰 prompt。
 */
class LiveAnalyzer(
    private val providerCatalog: ProviderCatalog,
    private val context: Context,
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
        locale: Locale,
    ): Outcome {
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException(context.getString(R.string.model_list_no_providers))
        val wantVision = mode == LiveAnalysisMode.AGGRESSIVE
        val modelSupportsVision = Modality.IMAGE in model.inputModalities
        val useVision = wantVision && modelSupportsVision && screenshotUri != null
        val degradedReason = when {
            !wantVision -> null
            !modelSupportsVision -> context.getString(R.string.live_mode_conservative)
            screenshotUri == null -> context.getString(R.string.live_mode_conservative)
            else -> null
        }

        val messages = if (useVision) {
            listOf(
                UIMessage.system(LivePrompt.visionSystem(locale)),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text(LivePrompt.user(snapshot, focus, actionLabel, locale)),
                        UIMessagePart.Image(url = screenshotUri),
                    ),
                ),
            )
        } else {
            listOf(
                UIMessage.system(LivePrompt.system(locale)),
                UIMessage.user(LivePrompt.user(snapshot, focus, actionLabel, locale)),
            )
        }

        val providerImpl = providerCatalog.text(provider)
        val result = providerImpl.complete(
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
            card = LivePrompt.parseCard(text, actionLabel, locale),
            usedVision = useVision,
            degradedReason = degradedReason,
        )
    }

    internal object LivePrompt {
        fun system(locale: Locale): String = if (locale.isChineseLocale()) {
            """
你是 AmberAgent 的 Live 伴随模式。你正在根据 Android 无障碍 UI 树做只读现场分析。

规则：
- 只基于提供的屏幕正文和少量 UI 树判断，不声称看到了 UI 树之外的视觉细节。
- 忽略状态栏、导航栏、输入法、分屏线、窗口框架、Tab、按钮、可点击状态、bounds、className。
- 不要分析"Canvas Window""分屏分割线""多窗口界面"等系统框架，除非屏幕正文明确与它相关。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合 360dp 宽的分屏侧栏阅读。
- 如果信息不足，直接说"不确定"，不要用泛泛建议填充。
""".trimIndent()
        } else {
            """
You are AmberAgent's Live companion. Analyze the current Android screen read-only from its Accessibility UI tree.

Rules:
- Use only the supplied screen text and limited UI tree; do not claim visual details outside that data.
- Ignore the status bar, navigation bar, IME, split-screen divider, window frame, tabs, buttons, click state, bounds, and className.
- Do not analyze "Canvas Window", "split-screen divider", or "multi-window UI" as system chrome unless the screen text is explicitly about it.
- Do not tell the user to click anything or pretend that an action was executed.
- Keep the answer short enough for a narrow companion panel.
- If the information is insufficient, say "Uncertain" instead of filling space with generic advice.
- Write all user-facing analysis content in ${locale.targetLanguage()}.
""".trimIndent()
        }

        fun user(
            snapshot: LiveScreenSnapshot,
            focus: String,
            actionLabel: String,
            locale: Locale,
        ): String = buildString {
            val chinese = locale.isChineseLocale()
            appendLine("${if (chinese) "当前应用" else "Current app"}: ${snapshot.appLabel.ifBlank { snapshot.packageName }}")
            appendLine("${if (chinese) "包名" else "Package"}: ${snapshot.packageName}")
            if (snapshot.title.isNotBlank()) {
                appendLine("${if (chinese) "窗口标题" else "Window title"}: ${snapshot.title}")
            }
            if (snapshot.windowDebugLabel.isNotBlank()) {
                appendLine("${if (chinese) "窗口候选" else "Window candidate"}: ${snapshot.windowDebugLabel}")
            }
            if (focus.isNotBlank()) {
                appendLine("${if (chinese) "用户关注点" else "User focus"}: $focus")
            }
            appendLine("${if (chinese) "任务" else "Task"}: ${promptActionLabel(actionLabel, chinese)}")
            appendLine(if (chinese) "输出格式：" else "Output format:")
            appendLine(actionContract(actionLabel, locale))
            appendLine(if (chinese) "屏幕正文：" else "Screen text:")
            appendLine(snapshot.contentText.ifBlank { snapshot.visibleText }.take(4_000))
            appendLine()
            appendLine(
                if (chinese) {
                    "补充 UI 树，仅用于消歧，不要复述控件框架："
                } else {
                    "Supplemental UI tree, for disambiguation only; do not repeat the control framework:"
                },
            )
            appendLine(snapshot.uiTree.take(4_000))
        }

        fun parseCard(text: String, actionLabel: String, locale: Locale): LiveModeCard {
            val chinese = locale.isChineseLocale()
            val conclusionNames = if (chinese) {
                arrayOf("结论", "总结", "回复", "正在看什么", "Conclusion", "Summary", "Reply", "What is visible")
            } else {
                arrayOf("Conclusion", "Summary", "Reply", "What is visible", "结论", "总结", "回复", "正在看什么")
            }
            val conclusion = firstNonBlankSection(text, *conclusionNames)
                .ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }
                .let { LiveUiTreeProcessor.cleanAnalysisItem(it).orEmpty() }
                .ifBlank { if (chinese) "不确定" else "Uncertain" }
            val keyPoints = when (actionLabel) {
                "找重点" -> sectionItems(text, 3, *(if (chinese) {
                    arrayOf("重点", "我觉得重点是", "关键信息", "Key points", "Key information")
                } else {
                    arrayOf("Key points", "Key information", "重点", "我觉得重点是", "关键信息")
                }))
                "总结" -> sectionItems(text, 3, *(if (chinese) {
                    arrayOf("事实", "重点", "关键信息", "Facts", "Key points", "Key information")
                } else {
                    arrayOf("Facts", "Key points", "Key information", "事实", "重点", "关键信息")
                }))
                "查风险" -> sectionItems(text, 3, *(if (chinese) {
                    arrayOf("风险", "风险点", "重点", "Risks", "Risk points", "Key points")
                } else {
                    arrayOf("Risks", "Risk points", "Key points", "风险", "风险点", "重点")
                }))
                "写回复" -> sectionItems(text, 1, *(if (chinese) {
                    arrayOf("语气", "依据", "重点", "Tone", "Basis", "Key points")
                } else {
                    arrayOf("Tone", "Basis", "Key points", "语气", "依据", "重点")
                }))
                else -> sectionItems(text, 3, *(if (chinese) {
                    arrayOf("重点", "我觉得重点是", "判断依据", "Key points", "Basis", "Key information")
                } else {
                    arrayOf("Key points", "Basis", "Key information", "重点", "我觉得重点是", "判断依据")
                }))
            }
            val suggestions = when (actionLabel) {
                "找重点" -> emptyList()
                "总结" -> emptyList()
                "找下一步" -> sectionItems(text, 3, *(if (chinese) {
                    arrayOf("下一步", "行动", "建议", "可以怎么做", "Next steps", "Actions", "Suggestions", "What to do")
                } else {
                    arrayOf("Next steps", "Actions", "Suggestions", "What to do", "下一步", "行动", "建议", "可以怎么做")
                }))
                "查风险" -> emptyList()
                "写回复" -> sectionItems(text, 1, *(if (chinese) {
                    arrayOf("回复", "回复草稿", "建议回复", "Reply", "Reply draft", "Suggested reply")
                } else {
                    arrayOf("Reply", "Reply draft", "Suggested reply", "回复", "回复草稿", "建议回复")
                })).ifEmpty { listOf(conclusion) }
                else -> sectionItems(text, 2, *(if (chinese) {
                    arrayOf("下一步", "建议", "可以怎么做", "Next steps", "Suggestions", "What to do")
                } else {
                    arrayOf("Next steps", "Suggestions", "What to do", "下一步", "建议", "可以怎么做")
                }))
            }
            return LiveModeCard(
                watching = conclusion,
                keyPoints = keyPoints,
                suggestions = suggestions,
                followUps = emptyList(),
                rawText = text,
            )
        }

        private fun actionContract(actionLabel: String, locale: Locale): String {
            return if (locale.isChineseLocale()) {
                when (actionLabel) {
                    "找重点" -> "结论：一句话\n重点：\n- 最重要的信息 1\n- 最重要的信息 2\n- 最重要的信息 3"
                    "总结" -> "总结：一句话\n事实：\n- 事实 1\n- 事实 2\n- 事实 3"
                    "找下一步" -> "结论：一句话\n下一步：\n- 建议 1\n- 建议 2\n- 建议 3"
                    "查风险" -> "结论：一句话\n风险：\n- 风险 1\n- 风险 2\n- 风险 3\n如果没有明确风险，只输出：结论：暂未发现明确风险"
                    "写回复" -> "回复：一条可直接发送的短回复\n语气：一句话说明"
                    else -> "结论：一句话\n重点：\n- 要点 1\n- 要点 2\n下一步：\n- 建议 1"
                }
            } else {
                when (actionLabel) {
                    "找重点" -> "Conclusion: one sentence\nKey points:\n- Key point 1\n- Key point 2\n- Key point 3"
                    "总结" -> "Summary: one sentence\nFacts:\n- Fact 1\n- Fact 2\n- Fact 3"
                    "找下一步" -> "Conclusion: one sentence\nNext steps:\n- Suggestion 1\n- Suggestion 2\n- Suggestion 3"
                    "查风险" -> "Conclusion: one sentence\nRisks:\n- Risk 1\n- Risk 2\n- Risk 3\nIf no clear risk is found, output only: Conclusion: No clear risk found"
                    "写回复" -> "Reply: one short reply ready to send\nTone: one sentence"
                    else -> "Conclusion: one sentence\nKey points:\n- Point 1\n- Point 2\nNext steps:\n- Suggestion 1"
                }
            }
        }

        private fun promptActionLabel(actionLabel: String, chinese: Boolean): String = when (actionLabel) {
            "屏幕分析" -> if (chinese) "屏幕分析" else "screen analysis"
            "找重点" -> if (chinese) "找重点" else "find key points"
            "总结" -> if (chinese) "总结" else "summarize"
            "找下一步" -> if (chinese) "找下一步" else "find the next step"
            "查风险" -> if (chinese) "查风险" else "check risks"
            "写回复" -> if (chinese) "写回复" else "draft a reply"
            else -> actionLabel
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
                        !trimmed.contains("：") && !trimmed.contains(":")
                }
            return LiveUiTreeProcessor.compactAnalysisItems(rawItems, maxItems)
        }

        /** 激进模式：截图为主信号 */
        fun visionSystem(locale: Locale): String = if (locale.isChineseLocale()) {
            """
你是 AmberAgent 的 Live 伴随模式。你会收到一张当前手机屏幕截图，以及辅助的无障碍文字提取。

规则：
- 以截图为主要依据；无障碍文字仅用于核对文本细节（如长串号码、链接）。
- 忽略状态栏、导航栏、输入法、悬浮窗等系统框架。
- 不要命令用户点击，不要假装已经执行操作。
- 输出要短，适合手机侧栏阅读。
- 如果信息不足，直接说"不确定"，不要用泛泛建议填充。
""".trimIndent()
        } else {
            """
You are AmberAgent's Live companion. You will receive a screenshot of the current phone screen and supplemental Accessibility text.

Rules:
- Use the screenshot as the primary signal; use Accessibility text only to verify text details such as long numbers or links.
- Ignore the status bar, navigation bar, IME, floating windows, and other system chrome.
- Do not tell the user to click anything or pretend that an action was executed.
- Keep the answer short enough for a phone side panel.
- If the information is insufficient, say "Uncertain" instead of filling space with generic advice.
- Write all user-facing analysis content in ${locale.targetLanguage()}.
""".trimIndent()
        }

        private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

        private fun Locale.targetLanguage(): String = when (language.lowercase(Locale.ROOT)) {
            "zh" -> if (country.equals("TW", ignoreCase = true) || script.equals("Hant", ignoreCase = true)) {
                "繁體中文"
            } else {
                "简体中文"
            }
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            else -> getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        }
    }
}
