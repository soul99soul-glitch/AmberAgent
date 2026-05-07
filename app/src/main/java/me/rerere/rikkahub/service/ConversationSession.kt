package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    initialPendingMessages: List<PendingUserMessage> = emptyList(),
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onPendingMessagesChanged: (Uuid, List<PendingUserMessage>) -> Unit = { _, _ -> },
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()

    private val _pendingUserMessages = MutableStateFlow(initialPendingMessages)
    val pendingUserMessages: StateFlow<List<PendingUserMessage>> = _pendingUserMessages.asStateFlow()

    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || pendingUserMessages.value.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            _generationJob.value = null
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    fun enqueuePendingUserMessage(message: PendingUserMessage): Boolean {
        var accepted = false
        _pendingUserMessages.update { current ->
            if (current.size >= MAX_PENDING_USER_MESSAGES) {
                current
            } else {
                accepted = true
                current + message
            }
        }
        if (accepted) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
            cancelIdleCheck()
        }
        return accepted
    }

    fun dequeueNextPendingUserMessage(): PendingUserMessage? {
        var next: PendingUserMessage? = null
        _pendingUserMessages.update { current ->
            next = current.firstOrNull()
            if (next == null) current else current.drop(1)
        }
        if (next != null) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return next
    }

    fun dequeueSteerPendingUserMessages(): List<PendingUserMessage> {
        var consumed = emptyList<PendingUserMessage>()
        _pendingUserMessages.update { current ->
            consumed = current.takeWhile { it.mode == PendingUserMessageMode.STEER }
            if (consumed.isEmpty()) current else current.drop(consumed.size)
        }
        if (consumed.isNotEmpty()) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return consumed
    }

    fun dequeueLeadingCollectableMessages(): List<PendingUserMessage> {
        var consumed = emptyList<PendingUserMessage>()
        _pendingUserMessages.update { current ->
            consumed = current.takeWhile { it.isCollectable }
            if (consumed.isEmpty()) current else current.drop(consumed.size)
        }
        if (consumed.isNotEmpty()) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return consumed
    }

    fun cancelPendingUserMessage(messageId: String): Boolean {
        var changed = false
        _pendingUserMessages.update { current ->
            val next = current.filterNot { it.id == messageId }
            changed = next.size != current.size
            next
        }
        if (changed) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return changed
    }

    fun movePendingUserMessage(messageId: String, offset: Int): Boolean {
        if (offset == 0) return false
        var changed = false
        _pendingUserMessages.update { current ->
            val index = current.indexOfFirst { it.id == messageId }
            if (index < 0) {
                current
            } else {
                val target = (index + offset).coerceIn(0, current.lastIndex)
                if (target == index) {
                    current
                } else {
                    changed = true
                    current.toMutableList().also { list ->
                        val item = list.removeAt(index)
                        list.add(target, item)
                    }
                }
            }
        }
        if (changed) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return changed
    }

    fun convertPendingSteerMessagesToFollowup(): Boolean {
        var changed = false
        _pendingUserMessages.update { current ->
            val next = current.map { message ->
                if (message.mode == PendingUserMessageMode.STEER) {
                    changed = true
                    message.asFollowup()
                } else {
                    message
                }
            }
            next
        }
        if (changed) {
            onPendingMessagesChanged(id, _pendingUserMessages.value)
        }
        return changed
    }

    fun clearPendingUserMessages() {
        if (_pendingUserMessages.value.isEmpty()) return
        _pendingUserMessages.value = emptyList()
        onPendingMessagesChanged(id, emptyList())
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
