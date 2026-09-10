package app.amber.feature.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool
import java.time.Instant
import java.time.ZoneId

fun createCalendarListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "calendar_list",
    description = "List Android calendar events after READ_CALENDAR is granted.",
    parameters = {
        obj(
            "from_epoch_ms" to integerProp("Start Unix epoch millis. Defaults to now."),
            "to_epoch_ms" to integerProp("End Unix epoch millis. Defaults to 7 days from start."),
            "limit" to integerProp("Maximum events. Defaults to 30."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("calendar_list", "读取日历事件", "calendar_read", input.safePreview()) {
            textJson {
                put("events", queryCalendarEvents(context, input))
            }
        }
    }
)

fun createCalendarCreateTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "calendar_create",
    description = "Create an Android calendar event. Requires WRITE_CALENDAR and explicit approval.",
    parameters = {
        obj(
            "title" to accessStringProp("Event title."),
            "start_time" to accessStringProp("ISO-8601 start time, for example 2026-05-03T10:00:00+08:00."),
            "end_time" to accessStringProp("ISO-8601 end time."),
            "start_epoch_ms" to integerProp("Start Unix epoch millis. Used if start_time is absent."),
            "end_epoch_ms" to integerProp("End Unix epoch millis. Used if end_time is absent."),
            "description" to accessStringProp("Optional event description."),
            "location" to accessStringProp("Optional event location."),
            required = listOf("title")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("calendar_create", "创建日历事件", "calendar_write", input.safePreview()) {
            val eventId = createCalendarEvent(context, input)
            textJson {
                put("success", true)
                put("event_id", eventId)
            }
        }
    }
)

fun createCalendarUpdateTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "calendar_update",
    description = "Update an Android calendar event by its stable event_id. Requires explicit approval.",
    parameters = {
        obj(
            "event_id" to integerProp("Stable numeric event ID returned by calendar_list or calendar_create."),
            "title" to accessStringProp("Optional replacement event title."),
            "start_time" to accessStringProp("Optional ISO-8601 replacement start time."),
            "start_epoch_ms" to integerProp("Optional replacement start Unix epoch millis."),
            "end_time" to accessStringProp("Optional ISO-8601 replacement end time."),
            "end_epoch_ms" to integerProp("Optional replacement end Unix epoch millis."),
            "description" to accessStringProp("Optional replacement event description."),
            "location" to accessStringProp("Optional replacement event location."),
            required = listOf("event_id")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("calendar_update", "更新日历事件", "calendar_write", input.safePreview()) {
            val eventId = input.requiredEventId()
            val current = requireCalendarEvent(context, eventId)
            val values = buildCalendarUpdateValues(input, current)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val updatedRows = context.contentResolver.update(uri, values, null, null)
            if (updatedRows <= 0) {
                if (queryCalendarEvent(context, eventId) == null) {
                    eventNotFound(eventId)
                }
                error("Failed to update calendar event: $eventId")
            }
            val updated = requireCalendarEvent(context, eventId)
            textJson {
                put("success", true)
                put("event_id", updated.eventId)
                put("title", updated.title)
                put("begin", updated.beginEpochMs)
                put("end", updated.endEpochMs)
                put("begin_epoch_ms", updated.beginEpochMs)
                put("end_epoch_ms", updated.endEpochMs)
            }
        }
    }
)

fun createCalendarDeleteTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "calendar_delete",
    description = "Delete an Android calendar event by its stable event_id. Requires explicit approval.",
    parameters = {
        obj(
            "event_id" to integerProp("Stable numeric event ID returned by calendar_list or calendar_create."),
            required = listOf("event_id")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        deps.trackSystemTool("calendar_delete", "删除日历事件", "calendar_write", input.safePreview()) {
            val eventId = input.requiredEventId()
            val current = requireCalendarEvent(context, eventId)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            val deletedRows = context.contentResolver.delete(uri, null, null)
            if (deletedRows <= 0) {
                if (queryCalendarEvent(context, eventId) == null) {
                    eventNotFound(eventId)
                }
                error("Failed to delete calendar event: $eventId")
            }
            textJson {
                put("success", true)
                put("event_id", current.eventId)
                put("title", current.title)
                put("begin_epoch_ms", current.beginEpochMs)
                put("end_epoch_ms", current.endEpochMs)
                put("deleted", true)
            }
        }
    }
)

private fun queryCalendarEvents(context: Context, input: JsonElement) = buildJsonArray {
    val now = System.currentTimeMillis()
    val from = input.long("from_epoch_ms") ?: now
    val to = input.long("to_epoch_ms") ?: (from + 7L * 24L * 60L * 60L * 1000L)
    val limit = input.limit(default = 30, max = 100)
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(from.toString())
        .appendPath(to.toString())
        .build()
    var count = 0
    context.contentResolver.query(
        uri,
        arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        ),
        null,
        null,
        "${CalendarContract.Instances.BEGIN} ASC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        while (cursor.moveToNext() && count < limit) {
            add(buildJsonObject {
                put("event_id", cursor.getLong(idIndex))
                put("title", cursor.getString(titleIndex).orEmpty())
                put("begin_epoch_ms", cursor.getLong(beginIndex))
                put("end_epoch_ms", cursor.getLong(endIndex))
                put("location", cursor.getString(locationIndex).orEmpty())
                put("calendar", cursor.getString(calendarIndex).orEmpty())
            })
            count++
        }
    }
}

private fun createCalendarEvent(context: Context, input: JsonElement): Long {
    val calendarId = firstWritableCalendarId(context)
    val start = input.timeMillis("start_time", "start_epoch_ms")
    val end = input.timeMillis("end_time", "end_epoch_ms")
    require(end > start) { "end_time must be after start_time" }
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, input.requiredString("title"))
        put(CalendarContract.Events.DESCRIPTION, input.string("description").orEmpty())
        put(CalendarContract.Events.EVENT_LOCATION, input.string("location").orEmpty())
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
    }
    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        ?: error("Failed to create calendar event")
    return ContentUris.parseId(uri)
}

private val CALENDAR_UPDATE_FIELDS = setOf(
    "event_id", "title", "start_time", "start_epoch_ms", "end_time", "end_epoch_ms", "description", "location"
)

internal data class CalendarEventSnapshot(
    val eventId: Long,
    val title: String,
    val beginEpochMs: Long,
    val endEpochMs: Long,
)

/**
 * Builds only the fields explicitly supplied by the caller. The current
 * snapshot supplies the untouched side of a time range for end > start
 * validation, but is never copied into ContentValues.
 */
internal fun buildCalendarUpdateValues(
    input: JsonElement,
    current: CalendarEventSnapshot,
): ContentValues {
    val objectInput = input.jsonObject
    require(objectInput.keys.all { it in CALENDAR_UPDATE_FIELDS }) { "Unsupported calendar update field" }
    require(objectInput.keys.any { it != "event_id" }) { "At least one event field is required" }

    val values = ContentValues()
    if (objectInput.containsKey("title")) {
        values.put(CalendarContract.Events.TITLE, input.string("title").orEmpty())
    }
    if (objectInput.containsKey("description")) {
        values.put(CalendarContract.Events.DESCRIPTION, input.string("description").orEmpty())
    }
    if (objectInput.containsKey("location")) {
        values.put(CalendarContract.Events.EVENT_LOCATION, input.string("location").orEmpty())
    }

    val start = if (objectInput.containsKey("start_time") || objectInput.containsKey("start_epoch_ms")) {
        input.timeMillis("start_time", "start_epoch_ms")
    } else {
        current.beginEpochMs
    }
    val end = if (objectInput.containsKey("end_time") || objectInput.containsKey("end_epoch_ms")) {
        input.timeMillis("end_time", "end_epoch_ms")
    } else {
        current.endEpochMs
    }
    require(end > start) { "end_time must be after start_time" }
    if (objectInput.containsKey("start_time") || objectInput.containsKey("start_epoch_ms")) {
        values.put(CalendarContract.Events.DTSTART, start)
    }
    if (objectInput.containsKey("end_time") || objectInput.containsKey("end_epoch_ms")) {
        values.put(CalendarContract.Events.DTEND, end)
    }
    return values
}

private fun JsonElement.requiredEventId(): Long =
    long("event_id")?.takeIf { it > 0 } ?: error("event_id is required and must be a positive numeric ID")

private fun requireCalendarEvent(context: Context, eventId: Long): CalendarEventSnapshot =
    requireCalendarEventSnapshot(eventId, queryCalendarEvent(context, eventId))

internal fun requireCalendarEventSnapshot(
    eventId: Long,
    snapshot: CalendarEventSnapshot?,
): CalendarEventSnapshot = snapshot ?: eventNotFound(eventId)

private fun queryCalendarEvent(context: Context, eventId: Long): CalendarEventSnapshot? {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
    return context.contentResolver.query(
        uri,
        arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        CalendarEventSnapshot(
            eventId = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)).orEmpty(),
            beginEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)),
            endEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)),
        )
    }
}

private fun eventNotFound(eventId: Long): Nothing = error("Event not found: $eventId")

private fun firstWritableCalendarId(context: Context): Long {
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            return cursor.getLong(0)
        }
    }
    error("No writable calendar found")
}

private fun JsonElement.timeMillis(isoName: String, epochName: String): Long {
    string(isoName)?.takeIf { it.isNotBlank() }?.let { return Instant.parse(it).toEpochMilli() }
    return long(epochName) ?: error("$isoName or $epochName is required")
}
