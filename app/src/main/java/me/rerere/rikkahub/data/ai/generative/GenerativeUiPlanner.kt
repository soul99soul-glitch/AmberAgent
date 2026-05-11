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
        val toolMediated = isToolMediatedRequest(latestUserText)
        return buildString {
            appendLine()
            appendLine("**Generative UI Planner**")
            when (classification) {
                WidgetUse.ENCOURAGE -> {
                    if (toolMediated) {
                        appendLine("The user asked for a visual artifact but also requested tools, skills, subagents, files, or external context.")
                        appendLine("Use the requested tool/skill/subagent first. Do NOT create a widget for routing, progress, plan, or status summaries.")
                        appendLine("Only emit a show-widget after the real tool/skill/subagent result exists and the widget is the final requested artifact.")
                    } else {
                        appendLine("The user asked for a direct visual. Create a concise widget to answer the request.")
                        appendLine("Start with one short sentence, then output the show-widget block immediately.")
                        appendLine("Prefer widget_code SVG for streaming; avoid renderer/spec unless the answer truly needs an interactive chart or slides.")
                        appendLine("Keep the SVG inside its viewBox with 24px padding; do not draw outside the card.")
                    }
                }

                WidgetUse.DISCOURAGE -> {
                    appendLine("Answer in normal Markdown. Do NOT create a widget.")
                }
            }
            appendLine("Never put show-widget JSON, SVG, HTML, renderer/spec, or widget code in hidden reasoning/thinking content.")
            appendLine("Avoid decorative widgets, oversized hero layouts, repeated prose inside the graphic, and visuals that duplicate the surrounding answer.")
        }
    }

    fun needsVisibleStreamingFallback(setting: GenerativeUiSetting, messages: List<UIMessage>): Boolean =
        shouldGenerateDirectWidgetWithoutTools(setting, messages)

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
        return directDraw && !needsExternalContext && !isToolMediatedRequest(text)
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
        // Only ENCOURAGE when the user explicitly asks for a visual artifact.
        // Chinese: unambiguous verb phrases (not bare "画" which matches 动画/漫画/画面).
        // English: \b-anchored to avoid substring hits like "paragraph" -> "graph".
        val explicitVisual = listOf(
            "画一下", "画一个", "画个", "画出", "可视化",
            "结构图", "流程图", "架构图", "组织图", "时序图", "思维导图",
            "幻灯片", "ppt", "presentation", "slides", "slide deck",
            "slide", "简报", "汇报", "演示",
        )
        val englishVisualRegex = Regex(
            """\b(draw|diagram|flowchart|chart|graph|visualize|visualise|sketch|plot)\b""",
            RegexOption.IGNORE_CASE,
        )
        val simpleKeywords = listOf("是什么", "怎么读", "翻译", "改写", "一句话", "简单说", "帮我改")
        return when {
            explicitVisual.any { it in lower } -> WidgetUse.ENCOURAGE
            englishVisualRegex.containsMatchIn(lower) -> WidgetUse.ENCOURAGE
            text.length < 48 || simpleKeywords.any { it in lower } -> WidgetUse.DISCOURAGE
            else -> WidgetUse.DISCOURAGE
        }
    }

    internal fun isToolMediatedRequest(text: String): Boolean {
        val lower = text.lowercase()
        val explicitDelegation = listOf(
            "@",
            "subagent",
            "agent",
            "skill",
            "技能",
            "插件",
            "工具",
            "调用",
            "委托",
            "delegate",
            "guizang",
            "workspace",
            "文件",
            "读取",
            "搜索",
            "联网",
            "网页",
            "网址",
            "http://",
            "https://",
        )
        return explicitDelegation.any { it in lower }
    }
}

enum class WidgetUse {
    ENCOURAGE,
    DISCOURAGE,
}
