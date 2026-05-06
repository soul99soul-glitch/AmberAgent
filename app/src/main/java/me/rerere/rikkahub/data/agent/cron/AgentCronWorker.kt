package me.rerere.rikkahub.data.agent.cron

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatService
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

class AgentCronWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val manager = get<AgentCronManager>()
        val task = manager.prepareTriggeredRun(taskId) ?: return Result.success()
        return runCatching {
            val conversationId = Uuid.parse(task.conversationId)
            val prompt = buildString {
                appendLine("这是 AmberAgent 手机端 Cron 定时任务触发。")
                appendLine("任务名称：${task.title}")
                appendLine("Cron：${task.cronExpression} (${task.timezoneId})")
                appendLine()
                append(task.prompt)
            }
            get<ChatService>().sendMessage(
                conversationId = conversationId,
                content = listOf(UIMessagePart.Text(prompt)),
                answer = true,
            )
            Result.success()
        }.getOrElse { error ->
            manager.markRunFailed(taskId, error.message ?: error::class.simpleName.orEmpty())
            Result.failure()
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
