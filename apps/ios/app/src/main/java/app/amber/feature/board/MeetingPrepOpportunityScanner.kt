package app.amber.feature.board

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.agent.data.db.entity.OpportunityType
class MeetingPrepOpportunityScanner(
    private val context: Context,
    private val opportunityRepository: OpportunityRepository,
    private val boardRepository: BoardRepository,
) : OpportunityScanner {
    override suspend fun scan(boardDate: String): Int =
        scanDays(DEFAULT_LOOKAHEAD_DAYS)

    suspend fun scanDays(days: Int): Int = withContext(Dispatchers.IO) {
        if (!hasReadCalendarPermission()) return@withContext 0
        val events = queryUpcomingMeetings(days.coerceIn(3, 14))
        var count = 0
        for (event in events) {
            val links = FEISHU_DOC_URL_REGEX.findAll("${event.description}\n${event.location}")
                .map { it.value.trimEnd(')', ']', '，', ',', '。') }
                .distinct()
                .take(MAX_DOC_LINKS)
                .toList()
            if (links.isEmpty()) continue
            val score = scoreMeeting(event, links)
            if (score < MIN_VISIBLE_SCORE) continue
            val scoreJson = scoreJson(
                "time_anchor" to 25,
                "object_anchor" to 25,
                "material_anchor" to 25,
                "actionable_next_step" to 15,
                "history" to 0,
            )
            val evidence = evidenceJson {
                put("meeting_title", event.title)
                put("begin", event.begin)
                put("end", event.end)
                put("location", event.location)
                put("calendar", event.calendar)
                put("description", event.description.take(500))
                put("doc_links", buildJsonArray { links.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            val summary = buildString {
                append("发现未来会议「").append(event.title).append("」关联了 ")
                append(links.size).append(" 份飞书文档。可以提前整理会议材料、补充论据或准备提纲。")
            }
            val entity = opportunity(
                type = OpportunityType.MEETING_PREP,
                sourceType = "calendar",
                sourceRef = "${event.eventId}@${event.begin}",
                title = "准备会议：${event.title}",
                summary = summary,
                evidenceJson = evidence,
                scoreJson = scoreJson,
                confidence = confidenceFromScore(score),
                suggestedActionsJson = buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("整理会议材料"))
                    add(kotlinx.serialization.json.JsonPrimitive("检查关联文档缺口"))
                    add(kotlinx.serialization.json.JsonPrimitive("准备补充提纲"))
                }.toString(),
                dueAt = event.begin,
                triggerAt = System.currentTimeMillis(),
                expiresAt = event.end + EXPIRE_AFTER_MEETING_MS,
            )
            if (opportunityRepository.upsertSuggested(entity) != null) count++
        }
        count
    }

    private fun hasReadCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun queryUpcomingMeetings(days: Int): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val from = now + MIN_LOOKAHEAD_MS
        val to = now + days * ONE_DAY_MS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val events = mutableListOf<CalendarEvent>()
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
                CalendarContract.Instances.ALL_DAY,
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
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            if (idIdx < 0 || titleIdx < 0 || beginIdx < 0) return@use
            while (cursor.moveToNext() && events.size < MAX_EVENTS) {
                val title = cursor.getString(titleIdx).orEmpty().trim()
                if (title.isBlank()) continue
                val begin = cursor.getLong(beginIdx)
                val end = if (endIdx >= 0) cursor.getLong(endIdx) else begin + DEFAULT_MEETING_MS
                val duration = end - begin
                if (allDayIdx >= 0 && cursor.getInt(allDayIdx) == 1) continue
                if (duration <= 0L || duration > MAX_MEETING_MS) continue
                events += CalendarEvent(
                    eventId = cursor.getLong(idIdx),
                    title = title.take(120),
                    description = if (descIdx >= 0) cursor.getString(descIdx).orEmpty().trim() else "",
                    begin = begin,
                    end = end,
                    location = if (locIdx >= 0) cursor.getString(locIdx).orEmpty().trim() else "",
                    calendar = if (calIdx >= 0) cursor.getString(calIdx).orEmpty().trim() else "",
                )
            }
        }
        return events
    }

    private suspend fun scoreMeeting(event: CalendarEvent, links: List<String>): Int {
        val sourceWeight = boardRepository.getWeight("calendar", "")?.weight ?: 0
        return (90 + sourceWeight).coerceIn(0, 100)
    }

    private data class CalendarEvent(
        val eventId: Long,
        val title: String,
        val description: String,
        val begin: Long,
        val end: Long,
        val location: String,
        val calendar: String,
    )

    companion object {
        private const val MAX_EVENTS = 80
        private const val MAX_DOC_LINKS = 8
        private const val MIN_VISIBLE_SCORE = 65
        private const val MIN_LOOKAHEAD_MS = 3L * 60L * 60L * 1000L
        private const val DEFAULT_LOOKAHEAD_DAYS = 7
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
        private const val DEFAULT_MEETING_MS = 60L * 60L * 1000L
        private const val MAX_MEETING_MS = 8L * 60L * 60L * 1000L
        private const val EXPIRE_AFTER_MEETING_MS = 6L * 60L * 60L * 1000L
        private val FEISHU_DOC_URL_REGEX = Regex("""https?://[^\s"'<>]*(?:feishu|larksuite)[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    }
}
