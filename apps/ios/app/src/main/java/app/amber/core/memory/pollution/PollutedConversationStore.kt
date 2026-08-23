package app.amber.core.memory.pollution

import app.amber.ai.ui.UIMessagePart
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.uuid.Uuid

/**
 * P2-a：被外部内容污染（POLLUTED）的会话 id 集合的轻量文件存储。
 *
 * 语义边界：污染的是「会话作为记忆抽取源」的资格——`ChatService` 的抽取 gate 在
 * 调 `extractAfterConversation` 前查这里；召回注入不受影响（双端都不改召回）。
 *
 * 不引入 Room/SDK 迁移：app 模块文件级 JSON 存储（filesDir/polluted-conversations.json）。
 * 线程安全：读改写在进程内锁内串行；add/remove 先写盘后更新内存——写盘失败记日志
 * 并回滚内存，置位不得阻塞工具结果（调用方不感知失败）。
 */
class PollutedConversationStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val lock = Any()

    @Volatile
    private var pollutedIds: Set<Uuid> = load()

    /** harness 置位（幂等，只增不减；复位走 [remove]）。 */
    fun add(conversationId: Uuid) {
        synchronized(lock) {
            if (conversationId in pollutedIds) return
            val updated = pollutedIds + conversationId
            if (persist(updated)) {
                pollutedIds = updated
            } else {
                Log.w(TAG, "PollutedConversationStore: failed to persist add of $conversationId")
            }
        }
    }

    /** 用户手动复位 POLLUTED→ENABLED。 */
    fun remove(conversationId: Uuid) {
        synchronized(lock) {
            if (conversationId !in pollutedIds) return
            val updated = pollutedIds - conversationId
            if (persist(updated)) {
                pollutedIds = updated
            } else {
                Log.w(TAG, "PollutedConversationStore: failed to persist remove of $conversationId")
            }
        }
    }

    fun contains(conversationId: Uuid): Boolean = conversationId in pollutedIds

    private fun load(): Set<Uuid> {
        if (!file.exists()) return emptySet()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return emptySet()
            json.decodeFromString<List<String>>(text).mapNotNull { raw ->
                runCatching { Uuid.parse(raw) }.getOrNull()
            }.toSet()
        }.getOrElse { error ->
            Log.w(TAG, "PollutedConversationStore: failed to load, treating as empty: ${error.message}")
            emptySet()
        }
    }

    private fun persist(ids: Set<Uuid>): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ids.map { it.toString() }))
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "PollutedConversationStore"

        fun create(context: android.content.Context): PollutedConversationStore =
            PollutedConversationStore(File(context.filesDir, "polluted-conversations.json"))
    }
}

/**
 * P2-a：外部上下文工具名判定（harness 拥有，不经模型）。与 iOS
 * `ConversationMemoryPollutionPolicy` 保持一致，避免双轨漂移：web 搜索 / 网页读取 /
 * MCP 直调与 `mcp__*` 展开；`wm_*` 待 URL 分类后纳入（计划 P2.5）。
 */
object MemoryPollutionTools {
    val POLLUTING_TOOL_NAMES: Set<String> = setOf("search_web", "scrape_web", "mcp_call_tool")

    fun isPollutingToolName(name: String): Boolean =
        name in POLLUTING_TOOL_NAMES || name.startsWith("mcp__")

    /**
     * 判定工具输出是否为失败（与 iOS `failureReason(from:)` 同一契约：ok=false /
     * denied / status 失败集合 / exit_code!=0）。只有成功输出才算外部上下文进入会话。
     * 对任意形状的输出零抛错——置位判定绝不允许打断生成循环。
     */
    fun isFailureOutput(output: List<UIMessagePart>, json: Json = Json): Boolean {
        for (part in output) {
            if (part !is UIMessagePart.Text) continue
            val trimmed = part.text.trim()
            if (trimmed.isEmpty()) continue
            val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: continue
            if (element !is JsonObject) continue
            val ok = element["ok"] as? JsonPrimitive
            if (ok?.booleanOrNull == false) return true
            val denied = element["denied"] as? JsonPrimitive
            if (denied?.booleanOrNull == true) return true
            val status = (element["status"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            if (status in FAILURE_STATUSES) return true
            val exitCode = element["exit_code"] as? JsonPrimitive
            if (exitCode?.intOrNull?.let { it != 0 } == true) return true
        }
        return false
    }

    private val FAILURE_STATUSES = setOf("error", "failed", "failure", "denied", "timed_out", "timeout")
}
