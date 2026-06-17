package app.amber.feature.board

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.openai.OpenAIKmpProvider
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class IosBoardAgent(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelId: String,
    private val chatCompletionsPath: String = "/chat/completions",
) : BoardAgentInterface {
    private val provider = OpenAIKmpProvider()

    override suspend fun generate(signals: List<BoardSignal>, setting: TodayBoardSetting): String {
        if (signals.isEmpty()) {
            return "今日暂无可用于生成看板的信号。"
        }
        if (apiKey.isBlank()) {
            return "无法生成今日看板：未配置 OpenAI API Key。"
        }
        if (modelId.isBlank()) {
            return "无法生成今日看板：未配置看板模型。"
        }

        val providerSetting = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "iOS Board Provider",
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
            chatCompletionsPath = chatCompletionsPath.ifBlank { "/chat/completions" },
            models = emptyList(),
        )
        val response = runCatching {
            provider.generateText(
                providerSetting = providerSetting,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        parts = listOf(
                            UIMessagePart.Text(
                                "你是 AmberAgent 的「今日看板」助理。基于用户提供的真实信号生成今日看板。" +
                                    "不要编造不存在的事项；没有待办时诚实输出空状态。",
                            ),
                        ),
                    ),
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(buildPrompt(signals, setting))),
                    ),
                ),
                params = TextGenerationParams(
                    model = Model(modelId = modelId),
                    temperature = 0.2f,
                    maxTokens = 900,
                ),
            )
        }

        return response.fold(
            onSuccess = { chunk ->
                chunk.choices.firstOrNull()?.message?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    ?.takeIf { it.isNotBlank() }
                    ?: "模型未返回今日看板内容。"
            },
            onFailure = { error ->
                "无法生成今日看板：${error.message ?: error::class.simpleName}"
            },
        )
    }

    private fun buildPrompt(signals: List<BoardSignal>, setting: TodayBoardSetting): String = buildString {
        appendLine("请基于以下真实 Board 信号生成今日看板。")
        appendLine()
        appendLine("## 输出要求")
        appendLine("- 使用中文。")
        appendLine("- 先给一句不超过 60 字的摘要。")
        appendLine("- 再列出最多 5 条用户需要行动、准备、回复、参加、提交、review 或确认的事项。")
        appendLine("- 每条事项必须引用信号来源 source_type/source_ref。")
        appendLine("- 如果只有时间锚点、没有真实待办，请明确说明暂无待办，不要硬凑内容。")
        appendLine("- 不要编造日历、消息、聊天或应用使用事实。")
        appendLine()
        appendLine("## 看板设置")
        appendLine("- enabled: ${setting.enabled}")
        appendLine("- density: ${setting.density.wireName}")
        appendLine("- enabled_sources: ${setting.enabledSources.joinToString()}")
        appendLine()
        appendLine("## 信号列表")
        signals.take(80).forEachIndexed { index, signal ->
            appendLine("### [$index] ${signal.sourceType}")
            appendLine("- source_ref: ${signal.sourceRef}")
            appendLine("- signal_time: ${signal.signalTime}")
            appendLine("- title: ${signal.title.take(120)}")
            appendLine("- content: ${signal.content.take(400).replace('\n', ' ')}")
            if (signal.metadataJson != "{}") {
                appendLine("- metadata: ${signal.metadataJson.take(300)}")
            }
            appendLine()
        }
    }
}
