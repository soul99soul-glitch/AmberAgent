package app.amber.feature.reminder

import app.amber.ai.core.Tool
import app.amber.feature.tools.accessStringProp
import app.amber.feature.tools.boolean
import app.amber.feature.tools.booleanProp
import app.amber.feature.tools.enumProp
import app.amber.feature.tools.integerProp
import app.amber.feature.tools.obj
import app.amber.feature.tools.requiredString
import app.amber.feature.tools.string
import app.amber.feature.tools.long
import app.amber.feature.tools.textJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Tool entry point for the app-owned reminder store and alarm scheduler. */
class ReminderTools(
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
) {
    fun getTools(): List<Tool> = listOf(listTool, createTool, updateTool, deleteTool)

    private val listTool = Tool(
        name = "reminder_list",
        description = "列出 AmberAgent 在本机保存的提醒及其下次触发时间。",
        parameters = { obj() },
        execute = {
            textJson {
                put("status", "ok")
                put("reminders", buildJsonArray {
                    store.list().forEach { add(it.toToolJson()) }
                })
            }
        },
    )

    private val createTool = Tool(
        name = "reminder_create",
        description = "创建一个本机提醒并立即写入持久存储及 Android 闹钟。",
        needsApproval = true,
        allowsAutoApproval = false,
        parameters = {
            obj(
                "title" to accessStringProp("提醒标题。"),
                "message" to accessStringProp("通知正文。"),
                "trigger_at_epoch_ms" to integerProp("首次触发时间，Unix epoch 毫秒。"),
                "recurrence" to enumProp("重复方式。默认 none。", RECURRENCE_VALUES),
                "enabled" to booleanProp("是否立即启用。默认 true。"),
                required = listOf("title", "message", "trigger_at_epoch_ms"),
            )
        },
        execute = { input ->
            val triggerAt = input.long("trigger_at_epoch_ms")
                ?.takeIf { it >= 0L }
                ?: error("trigger_at_epoch_ms must be a non-negative Unix epoch millis")
            val snapshot = ReminderSnapshot(
                id = UUID.randomUUID().toString(),
                title = input.requiredString("title"),
                message = input.requiredString("message"),
                triggerAtEpochMs = triggerAt,
                recurrence = input.recurrence(),
                nextFireAtEpochMs = triggerAt,
                enabled = input.boolean("enabled") ?: true,
            )
            store.upsert(snapshot)
            val schedule = scheduler.schedule(snapshot)
            textJson {
                put("status", "created")
                put("reminder", snapshot.toToolJson())
                putSchedule(schedule)
            }
        },
    )

    private val updateTool = Tool(
        name = "reminder_update",
        description = "更新本机提醒；省略的字段保持不变，并同步重排 Android 闹钟。",
        needsApproval = true,
        allowsAutoApproval = false,
        parameters = {
            obj(
                "reminder_id" to accessStringProp("reminder_list 返回的提醒 ID。"),
                "title" to accessStringProp("新的提醒标题。"),
                "message" to accessStringProp("新的通知正文。"),
                "trigger_at_epoch_ms" to integerProp("新的首次触发时间，Unix epoch 毫秒。"),
                "recurrence" to enumProp("新的重复方式。", RECURRENCE_VALUES),
                "enabled" to booleanProp("是否启用。"),
                required = listOf("reminder_id"),
            )
        },
        execute = { input ->
            val id = input.requiredString("reminder_id")
            val updated = store.update(id) { current ->
                val triggerAt = input.long("trigger_at_epoch_ms")?.also {
                    require(it >= 0L) { "trigger_at_epoch_ms must be a non-negative Unix epoch millis" }
                }
                current.copy(
                    title = input.string("title") ?: current.title,
                    message = input.string("message") ?: current.message,
                    triggerAtEpochMs = triggerAt ?: current.triggerAtEpochMs,
                    nextFireAtEpochMs = triggerAt ?: current.nextFireAtEpochMs,
                    recurrence = input.recurrenceOrNull() ?: current.recurrence,
                    enabled = input.boolean("enabled") ?: current.enabled,
                )
            }
            if (updated == null) {
                textJson {
                    put("status", "not_found")
                    put("reminder_id", id)
                }
            } else {
                val schedule = scheduler.schedule(updated)
                textJson {
                    put("status", "updated")
                    put("reminder", updated.toToolJson())
                    putSchedule(schedule)
                }
            }
        },
    )

    private val deleteTool = Tool(
        name = "reminder_delete",
        description = "删除本机提醒并取消它的 Android 闹钟。",
        needsApproval = true,
        allowsAutoApproval = false,
        parameters = {
            obj(
                "reminder_id" to accessStringProp("reminder_list 返回的提醒 ID。"),
                required = listOf("reminder_id"),
            )
        },
        execute = { input ->
            val id = input.requiredString("reminder_id")
            scheduler.cancel(id)
            val removed = store.remove(id)
            textJson {
                put("status", if (removed) "deleted" else "not_found")
                put("reminder_id", id)
            }
        },
    )

    private fun JsonElement.recurrence(): ReminderRecurrence =
        recurrenceOrNull() ?: ReminderRecurrence.NONE

    private fun JsonElement.recurrenceOrNull(): ReminderRecurrence? =
        string("recurrence")?.lowercase()?.let { value ->
            when (value) {
                "none" -> ReminderRecurrence.NONE
                "daily" -> ReminderRecurrence.DAILY
                "weekly" -> ReminderRecurrence.WEEKLY
                else -> error("recurrence must be one of: none, daily, weekly")
            }
        }

    private fun ReminderSnapshot.toToolJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        put("message", message)
        put("trigger_at_epoch_ms", triggerAtEpochMs)
        put("recurrence", recurrence.name.lowercase())
        nextFireAtEpochMs?.let { put("next_fire_at_epoch_ms", it) }
        put("enabled", enabled)
        put("fired_count", firedCount)
        lastFireKey?.let { put("last_fire_key", it) }
        put("corrupt", isCorrupt)
        corruptReason?.let { put("corrupt_reason", it) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putSchedule(result: ReminderScheduleResult) {
        put("schedule_precision", result.precision.name.lowercase())
        put("schedule_message", result.userMessage)
        result.fireKey?.let { put("fire_key", it) }
    }

    private companion object {
        val RECURRENCE_VALUES = listOf("none", "daily", "weekly")
    }
}
