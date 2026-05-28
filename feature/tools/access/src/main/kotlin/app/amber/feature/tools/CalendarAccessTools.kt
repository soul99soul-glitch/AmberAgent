package app.amber.feature.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
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
