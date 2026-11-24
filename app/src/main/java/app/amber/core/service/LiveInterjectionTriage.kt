package app.amber.core.service

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.core.ai.RunExecutionPhase
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

internal const val DEFAULT_INTERJECTION_TRIAGE_TIMEOUT_MS = 1_800L
internal const val DEFAULT_INTERJECTION_MAX_OUTPUT_TOKENS = 160

/** Four-way routing decision for a user message arriving while a task is active. */
enum class LiveInterjectionIntent(val wireValue: String) {
    STATUS_REPLY("status_reply"),
    STEER_ACTIVE("steer_active"),
    INDEPENDENT_NEW_TASK("independent_new_task"),
    STOP("stop"),
    ;

    companion object {
        fun fromWireValue(raw: String?): LiveInterjectionIntent? = when (raw?.trim()?.lowercase()) {
            "status_reply", "status", "progress", "check_status" -> STATUS_REPLY
            "steer_active", "steer", "redirect", "followup", "modify" -> STEER_ACTIVE
            "independent_new_task", "new_task", "parallel", "independent" -> INDEPENDENT_NEW_TASK
            "stop", "cancel", "abort" -> STOP
            else -> null
        }
    }
}

/** Real-time runtime snapshot of the active task fed into the fast-path classifier and local fallback. */
data class ActiveTaskRuntimeSnapshot(
    val taskId: String,
    val title: String,
    val phase: RunExecutionPhase,
    val activeToolName: String? = null,
    val completedToolCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val progressText: String? = null,
    val isQueued: Boolean = false,
)

data class LiveInterjectionDecision(
    val intent: LiveInterjectionIntent,
    val reply: String? = null,
    val stopAll: Boolean = false,
    val targetTaskId: String? = null,
    val usedFastLocalPath: Boolean = false,
    val fellBackToLocal: Boolean = false,
)

/** Lightweight auxiliary model transport used for fast-path interjection classification + short reply. */
fun interface LiveInterjectionAuxiliaryClient {
    suspend fun complete(
        conversationId: Uuid,
        prompt: String,
        maxTokens: Int,
    ): String?
}

/**
 * Production auxiliary client reusing the configured task chat model (falling back to the current
 * chat model), with reasoning disabled and strictly capped output tokens.
 */
class ProviderLiveInterjectionAuxiliaryClient(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
) : LiveInterjectionAuxiliaryClient {
    override suspend fun complete(
        conversationId: Uuid,
        prompt: String,
        maxTokens: Int,
    ): String? {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.resolveTaskChatModel(settings.suggestionModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val handler = providerCatalog.text(provider)
        val result = handler.complete(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                temperature = 0.0f,
                maxTokens = maxTokens,
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
                sessionId = "interjection-$conversationId",
            ),
        )
        return result.choices.firstOrNull()?.message?.toText()?.trim()
    }
}

/**
 * Fast-path live interjection triager:
 * 1. Zero-latency local routing for explicit stop/stop-all commands, explicit new-task prefixes,
 *    and explicitly targeted dock followups.
 * 2. One lightweight auxiliary model call (<=160 tokens, thinking off, strict 1.5s-2s timeout)
 *    carrying the real active task phase/tool/elapsed snapshot.
 * 3. Deterministic local fallback producing honest live status replies or steer confirmations
 *    whenever offline, unconfigured, timed out, or unparseable.
 */
class LiveInterjectionTriager(
    private val auxiliaryClient: LiveInterjectionAuxiliaryClient? = null,
    private val timeoutMs: Long = DEFAULT_INTERJECTION_TRIAGE_TIMEOUT_MS,
    private val maxOutputTokens: Int = DEFAULT_INTERJECTION_MAX_OUTPUT_TOKENS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun tryFastLocalDecision(
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
        selectedTargetTaskId: String? = null,
        hasNonTextParts: Boolean = false,
    ): LiveInterjectionDecision? {
        val normalized = normalizeInput(userText)
        if (normalized.isEmpty()) {
            if (hasNonTextParts) {
                return LiveInterjectionDecision(
                    intent = LiveInterjectionIntent.STEER_ACTIVE,
                    reply = buildLocalSteerAck(userText = "", snapshot = snapshot, hasNonTextParts = true),
                    targetTaskId = selectedTargetTaskId ?: snapshot.taskId,
                    usedFastLocalPath = true,
                )
            }
            return null
        }

        if (isExplicitStopAll(normalized)) {
            return LiveInterjectionDecision(
                intent = LiveInterjectionIntent.STOP,
                reply = if (isLikelyEnglish(userText)) {
                    "Stopped all active tasks."
                } else {
                    "已停止所有进行中的任务。"
                },
                stopAll = true,
                usedFastLocalPath = true,
            )
        }

        if (isExplicitStopSingle(normalized)) {
            return LiveInterjectionDecision(
                intent = LiveInterjectionIntent.STOP,
                reply = if (isLikelyEnglish(userText)) {
                    "Stopped the current task."
                } else {
                    "已停止当前任务。"
                },
                stopAll = false,
                targetTaskId = selectedTargetTaskId ?: snapshot.taskId,
                usedFastLocalPath = true,
            )
        }

        if (hasExplicitNewTaskPrefix(userText)) {
            return LiveInterjectionDecision(
                intent = LiveInterjectionIntent.INDEPENDENT_NEW_TASK,
                usedFastLocalPath = true,
            )
        }

        if (selectedTargetTaskId != null) {
            return LiveInterjectionDecision(
                intent = LiveInterjectionIntent.STEER_ACTIVE,
                reply = buildLocalSteerAck(userText = userText, snapshot = snapshot, hasNonTextParts = hasNonTextParts),
                targetTaskId = selectedTargetTaskId,
                usedFastLocalPath = true,
            )
        }

        return null
    }

    suspend fun classify(
        conversationId: Uuid,
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
        selectedTargetTaskId: String? = null,
        hasNonTextParts: Boolean = false,
    ): LiveInterjectionDecision {
        tryFastLocalDecision(
            userText = userText,
            snapshot = snapshot,
            selectedTargetTaskId = selectedTargetTaskId,
            hasNonTextParts = hasNonTextParts,
        )?.let { return it }

        val client = auxiliaryClient
        if (client != null) {
            val prompt = buildTriagePrompt(userText, snapshot)
            val raw = try {
                withTimeoutOrNull(timeoutMs) {
                    client.complete(
                        conversationId = conversationId,
                        prompt = prompt,
                        maxTokens = maxOutputTokens,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            parseAuxiliaryResponse(raw, userText, snapshot)?.let { return it }
        }

        return localFallbackDecision(
            userText = userText,
            snapshot = snapshot,
            hasNonTextParts = hasNonTextParts,
        )
    }

    internal fun localFallbackDecision(
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
        hasNonTextParts: Boolean = false,
    ): LiveInterjectionDecision {
        val normalized = normalizeInput(userText)
        if (!hasNonTextParts && isStatusInquiry(normalized)) {
            return LiveInterjectionDecision(
                intent = LiveInterjectionIntent.STATUS_REPLY,
                reply = buildLocalStatusReply(userText, snapshot),
                targetTaskId = snapshot.taskId,
                fellBackToLocal = true,
            )
        }
        return LiveInterjectionDecision(
            intent = LiveInterjectionIntent.STEER_ACTIVE,
            reply = buildLocalSteerAck(userText, snapshot, hasNonTextParts),
            targetTaskId = snapshot.taskId,
            fellBackToLocal = true,
        )
    }

    private fun parseAuxiliaryResponse(
        raw: String?,
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
    ): LiveInterjectionDecision? {
        if (raw.isNullOrBlank()) return null
        val jsonSlice = extractJsonObject(raw) ?: return null
        val obj = runCatching { json.parseToJsonElement(jsonSlice).jsonObject }.getOrNull() ?: return null
        val intent = LiveInterjectionIntent.fromWireValue(obj["intent"]?.jsonPrimitive?.contentOrNull)
            ?: return null
        val rawReply = obj["reply"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val stopAll = obj["stop_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
        val reply = when (intent) {
            LiveInterjectionIntent.STATUS_REPLY -> rawReply.ifBlank { buildLocalStatusReply(userText, snapshot) }
            LiveInterjectionIntent.STEER_ACTIVE -> rawReply.ifBlank { buildLocalSteerAck(userText, snapshot, false) }
            LiveInterjectionIntent.STOP -> rawReply.ifBlank {
                if (stopAll) "已停止所有进行中的任务。" else "已停止当前任务。"
            }
            LiveInterjectionIntent.INDEPENDENT_NEW_TASK -> null
        }
        return LiveInterjectionDecision(
            intent = intent,
            reply = reply,
            stopAll = stopAll,
            targetTaskId = snapshot.taskId,
            usedFastLocalPath = false,
            fellBackToLocal = false,
        )
    }

    private fun buildTriagePrompt(
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
    ): String {
        val phaseLabel = when {
            snapshot.isQueued -> "queued_for_slot"
            snapshot.phase == RunExecutionPhase.EXECUTING_TOOLS -> "executing_tool"
            snapshot.phase == RunExecutionPhase.WAITING_APPROVAL -> "waiting_approval"
            else -> "thinking"
        }
        val toolLine = snapshot.activeToolName?.takeIf { it.isNotBlank() } ?: "none"
        val progressLine = snapshot.progressText?.takeIf { it.isNotBlank() } ?: "none"
        return buildString {
            appendLine("You classify a user's live interjection while an assistant task is still running.")
            appendLine("Return ONLY a compact JSON object: {\"intent\":\"status_reply|steer_active|independent_new_task|stop\",\"reply\":\"short reply in user's language\",\"stop_all\":false}")
            appendLine("Rules:")
            appendLine("- status_reply: user asks about progress/status/whether stuck. Reply briefly using the real runtime state below. Do NOT claim finished.")
            appendLine("- steer_active: user redirects, refines, corrects, or adds constraints/context to the active task. Reply briefly acknowledging receipt and how you will adjust.")
            appendLine("- independent_new_task: user asks a clearly unrelated new task/question. Leave reply empty.")
            appendLine("- stop: user asks to stop/cancel the active task (set stop_all=true if stopping all tasks).")
            appendLine("Active task:")
            appendLine("- title: ${snapshot.title}")
            appendLine("- phase: $phaseLabel")
            appendLine("- active_tool: $toolLine")
            appendLine("- completed_tools: ${snapshot.completedToolCount}")
            appendLine("- elapsed_seconds: ${snapshot.elapsedSeconds}")
            appendLine("- progress: $progressLine")
            appendLine("User message: ${userText.trim().take(400)}")
        }
    }

    private fun buildLocalStatusReply(
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
    ): String {
        val english = isLikelyEnglish(userText)
        val elapsed = snapshot.elapsedSeconds.coerceAtLeast(0L)
        val tool = snapshot.activeToolName?.takeIf { it.isNotBlank() }
        val progress = snapshot.progressText?.takeIf { it.isNotBlank() }
        return if (english) {
            when {
                snapshot.isQueued -> "Still queued for an execution slot (${elapsed}s elapsed); it will start automatically as soon as a slot frees up."
                snapshot.phase == RunExecutionPhase.WAITING_APPROVAL && tool != null ->
                    "Waiting for your approval on tool `$tool` (${elapsed}s elapsed)."
                snapshot.phase == RunExecutionPhase.EXECUTING_TOOLS && tool != null ->
                    "Still running (${elapsed}s elapsed): currently executing tool `$tool` (${snapshot.completedToolCount} completed)."
                progress != null ->
                    "Still working (${elapsed}s elapsed): $progress"
                else ->
                    "Still thinking (${elapsed}s elapsed); I haven't stopped and will reply shortly."
            }
        } else {
            when {
                snapshot.isQueued -> "还在等待空闲执行槽位（已耗时 ${elapsed} 秒），空出后会立即继续。"
                snapshot.phase == RunExecutionPhase.WAITING_APPROVAL && tool != null ->
                    "当前卡在工具 `$tool` 的审批确认上（已耗时 ${elapsed} 秒），确认后就会继续。"
                snapshot.phase == RunExecutionPhase.EXECUTING_TOOLS && tool != null ->
                    "还在正常处理中（已耗时 ${elapsed} 秒），正在执行工具 `$tool`（已完成 ${snapshot.completedToolCount} 步）。"
                progress != null ->
                    "还在处理中（已耗时 ${elapsed} 秒），当前阶段：$progress"
                else ->
                    "还在思考生成中（已耗时 ${elapsed} 秒），没有卡死，马上继续。"
            }
        }
    }

    private fun buildLocalSteerAck(
        userText: String,
        snapshot: ActiveTaskRuntimeSnapshot,
        hasNonTextParts: Boolean,
    ): String {
        val english = isLikelyEnglish(userText)
        val summary = userText.trim().lineSequence().firstOrNull()?.take(36).orEmpty()
        val waitingOnTool = snapshot.phase == RunExecutionPhase.EXECUTING_TOOLS && !snapshot.activeToolName.isNullOrBlank()
        return if (english) {
            if (waitingOnTool) {
                "Got it — once `${snapshot.activeToolName}` finishes, I'll incorporate your update and replan."
            } else if (hasNonTextParts && summary.isBlank()) {
                "Got it — updating the current task with your attachment now."
            } else {
                "Got it — adjusting the current task now."
            }
        } else {
            if (waitingOnTool) {
                "收到，等当前工具 `${snapshot.activeToolName}` 安全完成后立刻按你的补充调整方案。"
            } else if (hasNonTextParts && summary.isBlank()) {
                "收到，正在结合你刚发的附件重新调整当前任务。"
            } else if (summary.isNotBlank()) {
                "收到，马上按「$summary」调整当前任务。"
            } else {
                "收到，马上按你的补充调整当前任务。"
            }
        }
    }

    private fun normalizeInput(text: String): String = text
        .trim()
        .lowercase()
        .trimEnd('。', '！', '!', '.', '？', '?', '，', ',', '～', '~')
        .trim()

    private fun isExplicitStopAll(normalized: String): Boolean = normalized in STOP_ALL_PHRASES

    private fun isExplicitStopSingle(normalized: String): Boolean = normalized in STOP_SINGLE_PHRASES

    private fun hasExplicitNewTaskPrefix(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        return NEW_TASK_PREFIXES.any { prefix -> lower.startsWith(prefix) }
    }

    private fun isStatusInquiry(normalized: String): Boolean {
        if (normalized.isEmpty() || normalized.length > 36) return false
        if (normalized in STATUS_EXACT_PHRASES) return true
        return STATUS_CONTAINS_PATTERNS.any { normalized.contains(it) }
    }

    private fun isLikelyEnglish(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val hasCjk = trimmed.any { it.code in 0x4E00..0x9FFF }
        return !hasCjk && trimmed.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    companion object {
        private val STOP_ALL_PHRASES = setOf(
            "全部停止",
            "全都停",
            "全停",
            "都停",
            "全部取消",
            "都别做了",
            "stop all",
            "cancel all",
            "abort all",
        )

        private val STOP_SINGLE_PHRASES = setOf(
            "停",
            "停下",
            "停一下",
            "先停",
            "先停下",
            "停止",
            "停止吧",
            "取消",
            "取消吧",
            "别做了",
            "不用做了",
            "算了",
            "不用了",
            "stop",
            "cancel",
            "abort",
            "never mind",
            "nevermind",
        )

        private val NEW_TASK_PREFIXES = listOf(
            "新任务:",
            "新任务：",
            "新问题:",
            "新问题：",
            "另外:",
            "另外：",
            "另起一个:",
            "另起一个：",
            "顺便问一下",
            "顺便问下",
            "new task:",
            "separate task:",
            "/new ",
        )

        private val STATUS_EXACT_PHRASES = setOf(
            "怎么了",
            "咋了",
            "卡住了吗",
            "卡住了",
            "卡了吗",
            "还在吗",
            "在吗",
            "好了吗",
            "完了吗",
            "进度",
            "进度如何",
            "进度怎么样",
            "到哪了",
            "做到哪了",
            "在干嘛",
            "在做什么",
            "怎么还没好",
            "还要多久",
            "status",
            "progress",
            "stuck",
            "are you stuck",
            "still running",
            "still working",
            "still there",
            "what's happening",
            "whats happening",
            "how's it going",
            "hows it going",
            "any update",
        )

        private val STATUS_CONTAINS_PATTERNS = listOf(
            "卡住了吗",
            "是不是卡了",
            "怎么还没",
            "怎么了",
            "到哪一步",
            "进度怎么",
            "还在跑吗",
            "还在处理吗",
            "are you stuck",
            "still working",
            "still running",
            "status update",
        )
    }
}
