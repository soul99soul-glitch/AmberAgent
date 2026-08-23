package app.amber.core.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure factory for the `get_time_info` agent tool — returns local time
 * fields the model can use without making a network call.
 *
 * Extracted from `LocalTools.timeTool` in M1.4 partial — first demo of the
 * "one tool per file" pattern the blueprint envisions for the 4 god tool
 * files. No state, no deps; the resulting Tool object can be cached behind
 * a `lazy { createTimeTool() }` if the caller wants.
 */
fun createTimeTool(): Tool = Tool(
    name = "get_time_info",
    description = """
        Get the current local date and time info from the device.
        Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    execute = {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime().withNano(0)
        val weekday = now.dayOfWeek
        val payload = buildJsonObject {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
            put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            put("weekday_index", weekday.value)
            put("date", date.toString())
            put("time", time.toString())
            put("datetime", now.withNano(0).toString())
            put("timezone", now.zone.id)
            put("utc_offset", now.offset.id)
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
