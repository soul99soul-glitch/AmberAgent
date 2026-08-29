package app.amber.feature.board.hotlist.deepread

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import java.util.Locale

object DeepReadHiddenAssistantFactory {
    fun create(settings: Settings, locale: Locale = Locale.getDefault()): Settings = settings.copy(
        systemPrompt = systemPrompt(locale),
        streamOutput = true,
        presetMessages = emptyList(),
        regexes = emptyList(),
        customHeaders = emptyList(),
        customBodies = emptyList(),
        enabledMcpServerIds = emptySet(),
        enabledModeInjectionIds = emptySet(),
        enabledLorebookIds = emptySet(),
        enabledSkills = emptySet(),
        messageTemplate = "{{ message }}",
        reasoningLevel = settings.reasoningLevel.takeUnless { it == ReasoningLevel.OFF } ?: ReasoningLevel.AUTO,
        // Keep provider/session defaults for max tokens. The section writer tools make each model
        // response small enough that we do not need a bespoke cap here.
        maxTokens = null,
    )

    private fun systemPrompt(locale: Locale): String {
        val chinese = locale.language.equals("zh", ignoreCase = true)
        val identity = if (chinese) {
            "你是 AmberAgent 今日看板的隐藏深度阅读 Agent。你的任务不是输出一篇普通聊天回答，而是基于来源研究并通过内部 writer tools 分段写入 News 杂志风深度阅读内容。"
        } else {
            "You are AmberAgent's hidden Deep Read agent for Today Board. Do not produce an ordinary chat answer; research the sources and write magazine-style Deep Read sections through the internal writer tools."
        }
        val rules = if (chinese) {
            """
            硬性规则：
            - UI 只消费 deep_read_write_overview / deep_read_write_narrative / deep_read_write_analysis / deep_read_write_extended_reading / deep_read_finish 的工具写入结果。
            - 你直接输出的长文、Markdown、完整 JSON、草稿正文都不会被 UI 展示。每完成一个段落，必须立刻调用对应 writer tool。
            - 不要把所有内容塞进一个 JSON。每个 writer tool 只写自己的小结构。
            - 拿到足够证据后优先调用当前段 writer tool；不要把时间耗在自由文本或连续搜索上。
            - 先基于来源研究，再写入；不允许凭模型记忆写当前事实。
            - 深度分析段允许你进行 reasoning，但最终可见内容仍必须通过 writer tool 写入。
            - 真实新闻图片只使用来源或搜索结果给出的图片 URL；不要生成或杜撰现场图。
            - writer tool 写入的所有用户可见字段使用中文；URL、专有名词和必要原文保持不变。
            """.trimIndent()
        } else {
            """
            Hard rules:
            - The UI consumes only the results written by deep_read_write_overview / deep_read_write_narrative / deep_read_write_analysis / deep_read_write_extended_reading / deep_read_finish.
            - The UI does not display long prose, Markdown, complete JSON, or draft text returned directly by you. Call the corresponding writer tool immediately after completing each section.
            - Do not put the whole article into one JSON object. Each writer tool writes only its own small structure.
            - After gathering enough evidence, prioritize the writer tool for the current section; do not spend the budget on free text or repeated searches.
            - Research from the supplied sources before writing; do not use model memory for current facts.
            - Reasoning is allowed for the analysis section, but all visible content must be written through a writer tool.
            - Use only image URLs supplied by sources or search results for real news images; never generate or fabricate a scene image.
            - Write all user-visible fields submitted through writer tools in English; keep URLs, proper nouns, and necessary original names unchanged.
            """.trimIndent()
        }
        return listOf(identity, rules).joinToString("\n\n")
    }
}
