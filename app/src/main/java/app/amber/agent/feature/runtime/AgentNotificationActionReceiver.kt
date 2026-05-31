package app.amber.agent.feature.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.launch
import app.amber.agent.AppScope
import app.amber.agent.RouteActivity
import app.amber.agent.data.db.entity.BoardTaskEntity
import app.amber.agent.data.db.entity.BoardTaskEventEntity
import app.amber.agent.data.db.entity.BoardTaskState
import app.amber.core.service.ChatService
import app.amber.feature.board.BoardTaskRepository
import app.amber.feature.board.BoardTaskRunReason
import app.amber.feature.board.BoardTaskRunner
import app.amber.feature.runtime.AgentLiveStatusNotifier
import app.amber.feature.runtime.BoardTaskLiveSnapshot
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

class AgentNotificationActionReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_GENERATION -> stopGeneration(intent)
            ACTION_BOARD_TASK_VIEW,
            ACTION_BOARD_TASK_CONTINUE,
            ACTION_BOARD_TASK_CANCEL,
            ACTION_BOARD_TASK_SNOOZE,
            ACTION_BOARD_TASK_INLINE_REPLY -> handleBoardTaskAction(context, intent)
            else -> return
        }
    }

    private fun stopGeneration(intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return
        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                get<ChatService>().stopGeneration(conversationId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleBoardTaskAction(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_BOARD_TASK_ID)?.takeIf { it.isNotBlank() } ?: return
        val pendingResult = goAsync()
        get<AppScope>().launch {
            try {
                val repository = get<BoardTaskRepository>()
                val notifier = get<AgentLiveStatusNotifier>()
                val runner = get<BoardTaskRunner>()
                when (intent.action) {
                    ACTION_BOARD_TASK_VIEW -> {
                        val task = repository.getTask(taskId) ?: return@launch
                        openTaskSession(context, task, repository.recentEvents(taskId))
                    }

                    ACTION_BOARD_TASK_CONTINUE -> {
                        val task = repository.continueTask(taskId) ?: return@launch
                        notifyActiveBoardTasks(repository, notifier, task, "已确认，等待任务会话继续推进")
                        runner.start(taskId, BoardTaskRunReason.CONTINUE)
                    }

                    ACTION_BOARD_TASK_CANCEL -> {
                        runner.cancel(taskId)
                        notifyActiveBoardTasks(repository, notifier)
                    }

                    ACTION_BOARD_TASK_SNOOZE -> {
                        val task = repository.getTask(taskId) ?: return@launch
                        if (task.state == BoardTaskState.IN_PROGRESS) {
                            repository.pauseForUser(taskId)?.let {
                                notifyActiveBoardTasks(repository, notifier, it, "已暂停，等待继续")
                            }
                        } else {
                            repository.snooze(taskId)
                            notifier.cancelBoardTask(taskId)
                            notifyActiveBoardTasks(repository, notifier)
                        }
                    }

                    ACTION_BOARD_TASK_INLINE_REPLY -> {
                        val reply = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(EXTRA_BOARD_TASK_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (reply.isNotBlank()) {
                            val task = repository.recordUserReply(taskId, reply) ?: return@launch
                            notifyActiveBoardTasks(repository, notifier, task, "已收到新的下一步指令")
                            runner.start(taskId, BoardTaskRunReason.USER_REPLY)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openTaskSession(
        context: Context,
        task: BoardTaskEntity,
        events: List<BoardTaskEventEntity>,
    ) {
        val launchIntent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(RouteActivity.EXTRA_OPEN_CHAT_PROMPT, task.executionSessionPrompt(events))
        }
        context.startActivity(launchIntent)
    }

    private suspend fun notifyActiveBoardTasks(
        repository: BoardTaskRepository,
        notifier: AgentLiveStatusNotifier,
        updatedTask: BoardTaskEntity? = null,
        updatedContent: String? = null,
    ) {
        val activeTasks = repository.activeNotificationTasks()
        val leadTask = activeTasks.firstOrNull() ?: return
        val content = if (updatedTask?.id == leadTask.id && updatedContent != null) {
            updatedContent
        } else {
            leadTask.defaultLiveContent()
        }
        notifier.notifyBoardTask(
            leadTask.toLiveSnapshot(
                content = content,
                activeTaskCount = activeTasks.size,
            )
        )
    }

    private fun BoardTaskEntity.toLiveSnapshot(
        content: String,
        activeTaskCount: Int = 1,
    ): BoardTaskLiveSnapshot =
        BoardTaskLiveSnapshot(
            taskId = id,
            title = title,
            state = state,
            chipText = chipText,
            content = content,
            updatedAt = updatedAt,
            activeTaskCount = activeTaskCount,
        )

    private fun BoardTaskEntity.defaultLiveContent(): String = when (state) {
        BoardTaskState.WAITING_USER -> "等待用户确认"
        BoardTaskState.IN_PROGRESS -> "已经派发，可打开任务会话继续推进"
        else -> title
    }

    private fun BoardTaskEntity.executionSessionPrompt(events: List<BoardTaskEventEntity>): String {
        val eventText = events
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { event -> "- ${event.type}: ${event.message}" }
            ?: "- 暂无任务事件"
        return """
            这是一个 Amber 任务执行会话，不是闲聊。

            任务：$title
            当前状态：${state.executionLabel()}
            摘要：$summary
            来源：$sourceType

            最近事件：
            $eventText

            请按下面格式推进：
            1. 当前目标
            2. 已完成步骤
            3. 下一步
            4. 是否需要我确认

            进展写回：当你推进、等待确认、受阻、完成或取消任务时，请调用 board_task_record 写入任务状态和事件。

            约束：高风险动作、系统权限动作、发送消息、删除/覆盖数据、ADB/Accessibility 自动操作都必须停在确认前。
        """.trimIndent()
    }

    private fun String.executionLabel(): String = when (this) {
        BoardTaskState.IN_PROGRESS -> "已经派发"
        BoardTaskState.WAITING_USER -> "等待确认"
        BoardTaskState.BLOCKED -> "遇到阻碍"
        BoardTaskState.DONE -> "任务完成"
        BoardTaskState.DISMISSED -> "已忽略"
        else -> this
    }

    companion object {
        const val ACTION_STOP_GENERATION = "app.amber.agent.action.STOP_GENERATION"
        const val ACTION_BOARD_TASK_VIEW = "app.amber.agent.action.BOARD_TASK_VIEW"
        const val ACTION_BOARD_TASK_CONTINUE = "app.amber.agent.action.BOARD_TASK_CONTINUE"
        const val ACTION_BOARD_TASK_CANCEL = "app.amber.agent.action.BOARD_TASK_CANCEL"
        const val ACTION_BOARD_TASK_SNOOZE = "app.amber.agent.action.BOARD_TASK_SNOOZE"
        const val ACTION_BOARD_TASK_INLINE_REPLY = "app.amber.agent.action.BOARD_TASK_INLINE_REPLY"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_BOARD_TASK_ID = "board_task_id"
        const val EXTRA_BOARD_TASK_REPLY = "board_task_reply"
    }
}
