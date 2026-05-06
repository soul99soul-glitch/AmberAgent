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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.agent.task.AgentTaskSnapshot
import me.rerere.rikkahub.data.agent.task.AgentTaskStatus
import me.rerere.rikkahub.data.agent.task.AgentTaskStore
import me.rerere.rikkahub.data.agent.task.toQueueState
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private val Context.agentCronDataStore by preferencesDataStore(name = "agent_cron_tasks")

class AgentCronManager(
    private val context: Context,
    private val json: Json,
    private val agentTaskStore: AgentTaskStore,
) {
    private val workManager = WorkManager.getInstance(context)

    val tasksFlow: Flow<List<AgentCronTask>> = context.agentCronDataStore.data
        .map { preferences ->
            decode(preferences[TASKS_KEY]).ifEmpty { decodeFromBackingFile() }
        }
        .distinctUntilChanged()

    suspend fun listTasks(): List<AgentCronTask> = tasksFlow.first()

    suspend fun listTasksSnapshot(): List<AgentCronTask> = withContext(Dispatchers.IO) {
        val fileTasks = decodeFromBackingFile()
        if (fileTasks.isNotEmpty()) {
            fileTasks
        } else {
            tasksFlow.first()
        }
    }

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
        agentTaskStore.register(task.toAgentTaskSnapshot())
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
        agentTaskStore.upsert(task.toAgentTaskSnapshot())
        task
    }

    suspend fun deleteTask(id: String): Boolean = withContext(Dispatchers.IO) {
        var removed = false
        replaceTasks { tasks ->
            removed = tasks.any { it.id == id }
            tasks.filterNot { it.id == id }
        }
        workManager.cancelUniqueWork(workName(id))
        if (removed) agentTaskStore.remove(id)
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
        prepared?.let { agentTaskStore.upsert(it.toAgentTaskSnapshot(status = AgentTaskStatus.QUEUED)) }
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
        agentTaskStore.update(id, status = AgentTaskStatus.FAILED, error = message.take(500))
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
            val current = decode(preferences[TASKS_KEY]).ifEmpty { decodeFromBackingFile() }
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
        }.recoverCatching {
            decodeLenient(raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeLenient(raw: String): List<AgentCronTask> {
        val root = json.parseToJsonElement(raw).jsonObject
        val tasks = root["tasks"]?.jsonArray.orEmpty()
        return tasks.mapNotNull { element ->
            val obj = element.jsonObject
            AgentCronTask(
                id = obj.string("id") ?: return@mapNotNull null,
                title = obj.string("title") ?: "",
                prompt = obj.string("prompt") ?: "",
                cronExpression = obj.string("cronExpression") ?: return@mapNotNull null,
                timezoneId = obj.string("timezoneId") ?: ZoneId.systemDefault().id,
                conversationId = obj.string("conversationId") ?: Uuid.random().toString(),
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                createdAtMs = obj["createdAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                updatedAtMs = obj["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                nextRunAtMs = obj["nextRunAtMs"]?.jsonPrimitive?.longOrNull,
                lastRunAtMs = obj["lastRunAtMs"]?.jsonPrimitive?.longOrNull,
                lastStatus = obj.string("lastStatus")?.let { rawStatus ->
                    AgentCronTaskStatus.entries.firstOrNull { it.name.equals(rawStatus, ignoreCase = true) }
                } ?: AgentCronTaskStatus.Idle,
                lastError = obj.string("lastError"),
                runCount = obj["runCount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun decodeFromBackingFile(): List<AgentCronTask> {
        val file = context.filesDir.resolve("datastore/agent_cron_tasks.preferences_pb")
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val start = text.indexOf("{\"tasks\":[")
        if (start < 0) return emptyList()
        val jsonText = extractJsonObject(text, start) ?: return emptyList()
        return runCatching { decodeLenient(jsonText) }.getOrDefault(emptyList())
    }

    private fun extractJsonObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until text.length) {
            val char = text[index]
            if (escaping) {
                escaping = false
                continue
            }
            when {
                char == '\\' && inString -> escaping = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun resolveZone(timezoneId: String?): ZoneId =
        runCatching { ZoneId.of(timezoneId?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())

    private fun workName(id: String) = "amberagent_cron_$id"

    private fun AgentCronTask.toAgentTaskSnapshot(status: AgentTaskStatus? = null) = AgentTaskSnapshot(
        taskId = id,
        type = "cron",
        title = title,
        sourceConversationId = conversationId,
        status = status ?: when {
            !enabled -> AgentTaskStatus.CANCELLED
            lastStatus == AgentCronTaskStatus.Failed -> AgentTaskStatus.FAILED
            lastStatus == AgentCronTaskStatus.Queued -> AgentTaskStatus.QUEUED
            else -> AgentTaskStatus.QUEUED
        },
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        sourceToolName = "cron_task_create",
        cancelCapability = false,
        summary = "cron=$cronExpression; timezone=$timezoneId; next_run_at_ms=${nextRunAtMs ?: "none"}",
        error = lastError,
    ).let { snapshot ->
        snapshot.copy(queueState = snapshot.status.toQueueState(snapshot.type))
    }

    companion object {
        private val TASKS_KEY = stringPreferencesKey("tasks_json")
        private const val WORK_TAG = "amberagent_cron"
    }
}
