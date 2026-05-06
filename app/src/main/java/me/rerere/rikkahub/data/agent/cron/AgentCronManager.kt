package me.rerere.rikkahub.data.agent.cron

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private val Context.agentCronDataStore by preferencesDataStore(name = "agent_cron_tasks")

class AgentCronManager(
    private val context: Context,
    private val json: Json,
) {
    private val workManager = WorkManager.getInstance(context)

    val tasksFlow: Flow<List<AgentCronTask>> = context.agentCronDataStore.data
        .map { preferences -> decode(preferences[TASKS_KEY]) }
        .distinctUntilChanged()

    suspend fun listTasks(): List<AgentCronTask> = tasksFlow.first()

    suspend fun createTask(
        title: String,
        prompt: String,
        cronExpression: String,
        timezoneId: String?,
        enabled: Boolean,
    ): AgentCronTask = withContext(Dispatchers.IO) {
        val safePrompt = prompt.trim()
        require(safePrompt.isNotBlank()) { "Cron task prompt cannot be blank" }
        val safeTitle = title.trim().ifBlank { safePrompt.take(32) }
        val zone = resolveZone(timezoneId)
        val expression = CronExpression.parse(cronExpression)
        val now = System.currentTimeMillis()
        val nextRunAt = if (enabled) expression.nextRunAfter(now, zone) else null
        if (enabled) {
            requireNotNull(nextRunAt) { "Cron expression has no run time in the next 366 days" }
        }
        val task = AgentCronTask(
            id = Uuid.random().toString(),
            title = safeTitle.take(80),
            prompt = safePrompt,
            cronExpression = expression.raw,
            timezoneId = zone.id,
            conversationId = Uuid.random().toString(),
            enabled = enabled,
            createdAtMs = now,
            updatedAtMs = now,
            nextRunAtMs = nextRunAt,
        )
        replaceTasks { tasks -> tasks + task }
        schedule(task)
        task
    }

    suspend fun updateTask(
        id: String,
        title: String? = null,
        prompt: String? = null,
        cronExpression: String? = null,
        timezoneId: String? = null,
        enabled: Boolean? = null,
    ): AgentCronTask = withContext(Dispatchers.IO) {
        var updated: AgentCronTask? = null
        replaceTasks { tasks ->
            tasks.map { task ->
                if (task.id != id) return@map task
                val nextTitle = title?.trim()?.takeIf { it.isNotBlank() }?.take(80) ?: task.title
                val nextPrompt = prompt?.trim()?.takeIf { it.isNotBlank() } ?: task.prompt
                val nextCron = cronExpression?.trim()?.takeIf { it.isNotBlank() } ?: task.cronExpression
                val nextZone = resolveZone(timezoneId ?: task.timezoneId)
                val nextEnabled = enabled ?: task.enabled
                val expression = CronExpression.parse(nextCron)
                val now = System.currentTimeMillis()
                val nextRunAt = if (nextEnabled) expression.nextRunAfter(now, nextZone) else null
                if (nextEnabled) {
                    requireNotNull(nextRunAt) { "Cron expression has no run time in the next 366 days" }
                }
                task.copy(
                    title = nextTitle,
                    prompt = nextPrompt,
                    cronExpression = expression.raw,
                    timezoneId = nextZone.id,
                    enabled = nextEnabled,
                    updatedAtMs = now,
                    nextRunAtMs = nextRunAt,
                    lastError = null,
                    lastStatus = AgentCronTaskStatus.Idle,
                ).also { updated = it }
            }
        }
        val task = requireNotNull(updated) { "Cron task not found: $id" }
        schedule(task)
        task
    }

    suspend fun deleteTask(id: String): Boolean = withContext(Dispatchers.IO) {
        var removed = false
        replaceTasks { tasks ->
            removed = tasks.any { it.id == id }
            tasks.filterNot { it.id == id }
        }
        workManager.cancelUniqueWork(workName(id))
        removed
    }

    suspend fun prepareTriggeredRun(id: String): AgentCronTask? = withContext(Dispatchers.IO) {
        var prepared: AgentCronTask? = null
        replaceTasks { tasks ->
            tasks.map { task ->
                if (task.id != id || !task.enabled) return@map task
                val now = System.currentTimeMillis()
                val nextRunAt = runCatching {
                    CronExpression.parse(task.cronExpression).nextRunAfter(now, resolveZone(task.timezoneId))
                }.getOrNull()
                task.copy(
                    lastRunAtMs = now,
                    nextRunAtMs = nextRunAt,
                    lastStatus = AgentCronTaskStatus.Queued,
                    lastError = null,
                    runCount = task.runCount + 1,
                    updatedAtMs = now,
                ).also { prepared = it }
            }
        }
        prepared?.let { schedule(it) }
        prepared
    }

    suspend fun markRunFailed(id: String, message: String) = withContext(Dispatchers.IO) {
        replaceTasks { tasks ->
            tasks.map { task ->
                if (task.id == id) {
                    task.copy(
                        lastStatus = AgentCronTaskStatus.Failed,
                        lastError = message.take(500),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                } else {
                    task
                }
            }
        }
    }

    suspend fun rescheduleAll() = withContext(Dispatchers.IO) {
        val tasks = listTasks()
        tasks.forEach { task ->
            if (task.enabled) {
                val next = task.nextRunAtMs ?: runCatching {
                    CronExpression.parse(task.cronExpression).nextRunAfter(System.currentTimeMillis(), resolveZone(task.timezoneId))
                }.getOrNull()
                schedule(task.copy(nextRunAtMs = next))
            } else {
                workManager.cancelUniqueWork(workName(task.id))
            }
        }
    }

    private suspend fun replaceTasks(transform: (List<AgentCronTask>) -> List<AgentCronTask>) {
        context.agentCronDataStore.edit { preferences ->
            val current = decode(preferences[TASKS_KEY])
            val next = transform(current).sortedBy { it.nextRunAtMs ?: Long.MAX_VALUE }
            preferences[TASKS_KEY] = json.encodeToString(AgentCronTaskStore.serializer(), AgentCronTaskStore(next))
        }
    }

    private fun schedule(task: AgentCronTask) {
        if (!task.enabled || task.nextRunAtMs == null) {
            workManager.cancelUniqueWork(workName(task.id))
            return
        }
        val delayMs = (task.nextRunAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AgentCronWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AgentCronWorker.KEY_TASK_ID to task.id))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(workName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun decode(raw: String?): List<AgentCronTask> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(AgentCronTaskStore.serializer(), raw).tasks
        }.getOrDefault(emptyList())
    }

    private fun resolveZone(timezoneId: String?): ZoneId =
        runCatching { ZoneId.of(timezoneId?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())

    private fun workName(id: String) = "amberagent_cron_$id"

    companion object {
        private val TASKS_KEY = stringPreferencesKey("tasks_json")
        private const val WORK_TAG = "amberagent_cron"
    }
}
