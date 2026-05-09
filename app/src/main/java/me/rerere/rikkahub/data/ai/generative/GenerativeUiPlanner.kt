package me.rerere.rikkahub.data.ai.generative

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.GenerativeUiSetting

object GenerativeUiPlanner {
    fun buildPrompt(setting: GenerativeUiSetting, messages: List<UIMessage>): String {
        if (!setting.enabled) return ""
        val latestUserText = latestUserText(messages)
        if (latestUserText.isBlank()) return ""

        val classification = classify(latestUserText)
        return buildString {
            appendLine()
            appendLine("**Generative UI Planner**")
            when (classification) {
                WidgetUse.ENCOURAGE -> {
                    appendLine("For this request, prefer a concise widget if it makes the answer easier to inspect.")
                    appendLine("Good widget types: process flow, architecture map, comparison table, timeline, risk matrix, data chart, or status card.")
                    appendLine("Start the visible assistant answer immediately with a short sentence, then output the show-widget block in visible content.")
                    appendLine("Prefer widget_code SVG for this request so the card can appear progressively during streaming; avoid renderer/spec when the user expects a drawing to form live.")
                    appendLine("Do not call tools just to draw the widget. Static SVG/HTML must be written directly as visible show-widget content.")
                    appendLine("Keep the SVG within its viewBox. Leave 24px padding on every side and reduce detail density instead of drawing outside the card.")
                    appendLine("If you use hidden reasoning, keep it brief; do not spend a long hidden phase before visible content appears.")
                }

                WidgetUse.ALLOW -> {
                    appendLine("Use a widget only if it materially improves clarity; otherwise answer in normal Markdown.")
                }

                WidgetUse.DISCOURAGE -> {
                    appendLine("Prefer normal Markdown for this request. Do not create a widget unless the user explicitly asks for a visual card, diagram, chart, or mockup.")
                }
            }
            appendLine("Never put show-widget JSON, SVG, HTML, renderer/spec, or widget code in hidden reasoning/thinking content.")
            appendLine("Avoid decorative widgets, oversized hero layouts, repeated prose inside the graphic, and visuals that duplicate the surrounding answer.")
        }
    }

    fun needsVisibleStreamingFallback(setting: GenerativeUiSetting, messages: List<UIMessage>): Boolean =
        setting.enabled && classify(latestUserText(messages)) == WidgetUse.ENCOURAGE

    fun shouldGenerateDirectWidgetWithoutTools(setting: GenerativeUiSetting, messages: List<UIMessage>): Boolean {
        if (!setting.enabled) return false
        val text = latestUserText(messages)
        if (classify(text) != WidgetUse.ENCOURAGE) return false
        val lower = text.lowercase()
        val directDraw = listOf(
            "画", "绘制", "画一下", "画一个", "结构图", "流程图", "架构图", "组织图",
            "draw", "sketch", "diagram", "flowchart", "org chart",
        ).any { it in lower }
        val needsExternalContext = listOf(
            "搜索", "查一下", "联网", "网页", "网址", "http://", "https://", "url",
            "读取", "文件", "workspace", "屏幕", "当前页面", "用工具", "调用工具",
        ).any { it in lower }
        return directDraw && !needsExternalContext
    }

    private fun latestUserText(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    internal fun classify(text: String): WidgetUse {
        val lower = text.lowercase()
        val visualKeywords = listOf(
            "可视化", "图", "图表", "流程", "架构", "时间线", "路线图", "对比", "矩阵", "风险",
            "画", "绘制", "计划", "plan", "draw", "diagram", "chart", "graph", "timeline", "roadmap", "architecture",
            "compare", "matrix", "risk", "ui", "界面", "mockup", "live", "伴随",
        )
        val simpleKeywords = listOf("是什么", "怎么读", "翻译", "改写", "一句话", "简单说")
        val simpleEnglishReply = Regex("""\b(yes|no)\b""").containsMatchIn(lower)
        return when {
            visualKeywords.any { it in lower } -> WidgetUse.ENCOURAGE
            text.length < 48 || simpleKeywords.any { it in lower } || simpleEnglishReply -> WidgetUse.DISCOURAGE
            else -> WidgetUse.ALLOW
        }
    }
}

enum class WidgetUse {
    ENCOURAGE,
    ALLOW,
    DISCOURAGE,
}
