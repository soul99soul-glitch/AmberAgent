package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object MiniAppPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (!ctx.settings.agentRuntime.miniApp.enabled) return messages
        if (lastUserIndex < 0) return messages
        val message = messages[lastUserIndex]
        val textIndex = message.parts.indexOfLast { it is UIMessagePart.Text }
        if (textIndex < 0) return messages
        val text = (message.parts[textIndex] as UIMessagePart.Text).text
        if (!isExplicitMiniAppRequest(text)) return messages

        val updatedParts = message.parts.toMutableList()
        updatedParts[textIndex] = UIMessagePart.Text(
            text = text.trimEnd() + "\n\n" + miniAppInstruction,
            metadata = (message.parts[textIndex] as UIMessagePart.Text).metadata,
        )
        return messages.toMutableList().also {
            it[lastUserIndex] = message.copy(parts = updatedParts)
        }
    }

    fun isExplicitMiniAppRequest(text: String): Boolean {
        val normalized = text.lowercase()
        return "miniapp" in normalized ||
            "mini app" in normalized ||
            "小应用" in text ||
            "小程序" in text
    }

    private val miniAppInstruction = """
        请按 AmberAgent MiniApp V2 输出一个严格 JSON 对象，不要输出 Markdown 解释或代码围栏。
        Schema:
        {
          "title": "1-20 字标题",
          "description": "1-80 字描述",
          "icon": "最多 2 个字符",
          "category": "tool|game|info|custom",
          "permissions": ["storage","toast","theme","network","externalImages","search","clipboard.copy","host.updateBoardSummary"],
          "html": "<!DOCTYPE html>..."
        }
        约束：只生成单文件 HTML；不要使用 script src、iframe、form、eval、new Function、import()、原生 fetch、XMLHttpRequest、WebSocket、localStorage、sessionStorage、geolocation。
        图片允许 data:image/... 或 https:// 图片 URL；不要使用 http://、相对路径、file/content/blob URL。外链图片必须声明 externalImages 权限。
        网络只能通过 await Amber.fetch({ url, method, headers, body, responseType })，必须声明 network 权限；不要调用 window.fetch。
        搜索只能通过 await Amber.search({ query, limit })，必须声明 search 权限；搜索结果是 title/url/snippet/source/publishedAt 的结构化列表。
        剪贴板写入只能用 await Amber.clipboard.copy(text)，必须声明 clipboard.copy；不能读取剪贴板。
        持久化用 await Amber.storage.get/set/remove，提示用 await Amber.toast，主题用 await Amber.host.getTheme。
        如做新闻、阅读、列表类小应用，更新按钮可以调用 Amber.search 或 Amber.fetch 获取新内容；如果未声明对应权限，就只能更新本地状态或演示数据。
        新闻、阅读、列表类小应用必须支持纵向滚动；不要把 body 固定成 overflow:hidden 或只能显示一屏，除非用户明确要求全屏游戏/计时器类工具。
    """.trimIndent()
}
