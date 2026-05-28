package app.amber.feature.board.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.board.BoardSignalSourceType
import app.amber.core.ai.mcp.McpManager

/**
 * Pulls Feishu unread/recent IM messages via the user's configured Feishu MCP server.
 *
 * Strategy is deliberately conservative: every collect() asks the MCP for the latest N
 * unread items and emits one signal per message. Real-time push isn't part of MVP — MCP
 * doesn't currently expose webhook-style events through this layer.
 *
 * Tool discovery matches by name prefix: any installed tool whose name starts with
 * `lark-im` and contains `unread` / `recent` / `latest` is treated as a candidate. We
 * call the first match. This avoids hard-coding a specific server's tool name while still
 * being predictable. If no candidate exists, the collector quietly returns nothing — the
 * user just hasn't installed Feishu MCP, which is fine.
 *
 * Each tool returns text content in MCP UIMessagePart.Text form; we attempt to parse it
 * as JSON and extract a list. If parsing fails we surface the raw text as a single signal
 * so the agent can at least see it.
 */
class FeishuMessageSignalCollector(
    private val mcpManager: McpManager,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.FEISHU_MSG

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        val toolName = pickToolName() ?: return@withContext emptyList()

        val args = buildJsonObject {
            put("limit", JsonPrimitive(limit.coerceIn(1, 50)))
        }
        val parts = runCatching { mcpManager.callTool(toolName, args) }
            .getOrElse { return@withContext emptyList() }

        val texts = parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        if (texts.isEmpty()) return@withContext emptyList()

        // Try structured parse first; fall back to one synthetic signal if the tool
        // returned plain text.
        val structured = parseStructured(texts)
        if (structured.isNotEmpty()) return@withContext structured.take(limit)

        val now = System.currentTimeMillis()
        val raw = texts.joinToString("\n").take(2_000)
        // Stable per-day ref so repeated bulk fallbacks within the same day don't pile up
        // as separate signals. ContentHash dedup will still catch identical bodies.
        val today = java.time.LocalDate.now().toString()
        listOf(
            RawBoardSignal(
                sourceType = BoardSignalSourceType.FEISHU_MSG,
                sourceRef = "feishu_im_bulk:$today",
                title = "飞书未读消息摘要",
                content = raw,
                signalTime = now,
            ),
        )
    }

    private fun pickToolName(): String? {
        val tools = runCatching { mcpManager.getAllAvailableTools() }.getOrNull().orEmpty()
        return tools
            .firstOrNull { tool ->
                val n = tool.name.lowercase()
                (n.startsWith("lark-im") || n.startsWith("feishu-im") || n.contains("im_unread")) &&
                    (n.contains("unread") || n.contains("recent") || n.contains("latest"))
            }?.name
    }

    private fun parseStructured(texts: List<String>): List<RawBoardSignal> {
        val now = System.currentTimeMillis()
        val results = mutableListOf<RawBoardSignal>()
        for (text in texts) {
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: continue
            val items = when {
                element is JsonObject && element["messages"] != null ->
                    element["messages"]!!.jsonArray
                element is JsonObject && element["items"] != null ->
                    element["items"]!!.jsonArray
                element is JsonObject && element["data"] is JsonObject &&
                    element["data"]!!.jsonObject["items"] != null ->
                    element["data"]!!.jsonObject["items"]!!.jsonArray
                else -> runCatching { element.jsonArray }.getOrNull()
            } ?: continue
            for (item in items) {
                val obj = item as? JsonObject ?: continue
                val title = obj.stringField("sender", "from", "user", "chat_name", "title")
                    ?: "飞书消息"
                val content = obj.stringField("content", "text", "message", "preview").orEmpty()
                if (content.isBlank()) continue
                val msgId = obj.stringField("message_id", "id", "msg_id") ?: "${title.hashCode()}-${content.hashCode()}"
                val time = obj["create_time"]?.jsonPrimitive?.runCatching { long }?.getOrNull()
                    ?: obj["time"]?.jsonPrimitive?.runCatching { long }?.getOrNull()
                    ?: now
                results += RawBoardSignal(
                    sourceType = BoardSignalSourceType.FEISHU_MSG,
                    sourceRef = msgId,
                    title = title.take(120),
                    content = content.take(2_000),
                    signalTime = time,
                    metadataJson = """{"raw_keys":${obj.keys.size}}""",
                )
            }
        }
        return results
    }

    private fun JsonObject.stringField(vararg keys: String): String? {
        for (key in keys) {
            val v = (this[key] as? JsonPrimitive)?.contentOrNull
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    companion object {
        private val Json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
