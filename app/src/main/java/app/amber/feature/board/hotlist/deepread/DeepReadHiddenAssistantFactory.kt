package app.amber.feature.board.hotlist.deepread

import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

internal object DeepReadHiddenAssistantFactory {
    fun create(settings: Settings): Assistant {
        val base = settings.getCurrentAssistant()
        return base.copy(
            id = Uuid.random(),
            name = "Deep Read Agent",
            systemPrompt = systemPrompt(),
            streamOutput = true,
            enableMemory = false,
            useGlobalMemory = false,
            enableRecentChatsReference = false,
            presetMessages = emptyList(),
            quickMessageIds = emptySet(),
            regexes = emptyList(),
            customHeaders = emptyList(),
            customBodies = emptyList(),
            mcpServers = emptySet(),
            localTools = emptyList(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            enabledSkills = emptySet(),
            enableTimeReminder = false,
            messageTemplate = "{{ message }}",
            reasoningLevel = base.reasoningLevel.takeUnless { it == ReasoningLevel.OFF } ?: ReasoningLevel.AUTO,
            // Keep provider/session defaults for max tokens. The section writer tools make each model
            // response small enough that we do not need a bespoke cap here.
            maxTokens = null,
        )
    }

    private fun systemPrompt(): String = """
        你是 AmberAgent 今日看板的隐藏深度阅读 Agent。你的任务不是输出一篇普通聊天回答，而是研究、验真并通过内部 writer tools 分段写入 News 杂志风深度阅读内容。

        硬性规则：
        - UI 只消费 deep_read_write_overview / deep_read_write_narrative / deep_read_write_analysis / deep_read_write_extended_reading / deep_read_finish 的工具写入结果。
        - 你直接输出的长文、Markdown、完整 JSON、草稿正文都不会被 UI 展示。每完成一个段落，必须立刻调用对应 writer tool。
        - 不要把所有内容塞进一个 JSON。每个 writer tool 只写自己的小结构。
        - 底层单次模型/工具链路在设备上可能约 45 秒中断；拿到足够证据后优先在 35 秒内调用当前段 writer tool，不要把时间耗在自由文本或连续搜索上。
        - 先研究和验真，再写入；不允许凭模型记忆写当前事实。
        - 深度分析段允许你进行 reasoning，但最终可见内容仍必须通过 writer tool 写入。
        - 真实新闻图片只使用来源或搜索结果给出的图片 URL；不要生成或杜撰现场图。

        ${DeepReadFactCheckGuidance.prompt}
    """.trimIndent()
}
