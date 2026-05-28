package app.amber.feature.board.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.feature.board.BoardSignalSourceType

/**
 * Pulls upcoming and recently-started calendar events from the system CalendarProvider.
 *
 * Window strategy: by default we look at events between (now - 1 hour) and (now + 24
 * hours). The "-1 hour" tail captures meetings that just ended so the agent can mention
 * them in TODO suggestions ("you had X meeting earlier, do you need to follow up?"); the
 * "+24 hours" head covers everything the user might still need to prepare for today.
 *
 * Returns an empty list (without throwing) when the calendar permission is missing OR
 * when the OEM CalendarProvider doesn't expose the columns we expect — the collector
 * layer must be best-effort and never crash the worker.
 */
class CalendarSignalCollector(
    private val context: Context,
    private val lookBackMs: Long = 60L * 60L * 1000L,
    private val lookAheadMs: Long = 24L * 60L * 60L * 1000L,
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.CALENDAR

    override suspend fun collect(limit: Int): List<RawBoardSignal> = withContext(Dispatchers.IO) {
        if (!hasReadCalendarPermission()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val from = now - lookBackMs
        val to = now + lookAheadMs

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()

        runCatching {
            val results = mutableListOf<RawBoardSignal>()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                ),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val descIdx = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val calIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

                if (idIdx < 0 || titleIdx < 0 || beginIdx < 0) return@use

                while (cursor.moveToNext() && results.size < limit) {
                    val eventId = cursor.getLong(idIdx)
                    val begin = cursor.getLong(beginIdx)
                    val end = if (endIdx >= 0) cursor.getLong(endIdx) else begin + 3600_000L
                    val title = cursor.getString(titleIdx).orEmpty().trim()
                    if (title.isBlank()) continue
                    val description = if (descIdx >= 0) cursor.getString(descIdx).orEmpty().trim() else ""
                    val location = if (locIdx >= 0) cursor.getString(locIdx).orEmpty().trim() else ""
                    val calendar = if (calIdx >= 0) cursor.getString(calIdx).orEmpty().trim() else ""

                    val content = buildString {
                        append("时间：")
                        append(formatRange(begin, end))
                        if (location.isNotBlank()) {
                            append("\n地点：").append(location)
                        }
                        if (calendar.isNotBlank()) {
                            append("\n日历：").append(calendar)
                        }
                        if (description.isNotBlank()) {
                            append("\n说明：").append(description.take(800))
                        }
                    }

                    // sourceRef = eventId + begin so recurring instances dedup per occurrence.
                    val sourceRef = "$eventId@$begin"
                    results += RawBoardSignal(
                        sourceType = BoardSignalSourceType.CALENDAR,
                        sourceRef = sourceRef,
                        title = title.take(120),
                        content = content,
                        signalTime = begin,
                        metadataJson = """{"event_id":$eventId,"begin":$begin,"end":$end}""",
                    )
                }
            }
            results.toList()
        }.getOrElse { emptyList() }
    }

    private fun hasReadCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatRange(begin: Long, end: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val fmtDate = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        val fmtTime = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val from = java.time.Instant.ofEpochMilli(begin).atZone(zone)
        val to = java.time.Instant.ofEpochMilli(end).atZone(zone)
        return if (from.toLocalDate() == to.toLocalDate()) {
            "${from.format(fmtDate)} - ${to.format(fmtTime)}"
        } else {
            "${from.format(fmtDate)} - ${to.format(fmtDate)}"
        }
    }
}
