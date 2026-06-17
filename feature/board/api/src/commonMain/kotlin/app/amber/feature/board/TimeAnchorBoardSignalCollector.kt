package app.amber.feature.board

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Emits low-weight time anchors so iOS manual Board runs always have an honest signal.
 */
class TimeAnchorBoardSignalCollector(
    private val triggerHourProvider: () -> List<String> = { listOf("08:00", "12:00", "18:00") },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : BoardSignalCollectorInterface {
    override val sourceType: String = BoardSignalSourceType.TIME

    override suspend fun collect(context: BoardCollectContext): List<BoardSignal> {
        val zone = timeZoneProvider()
        val nowMs = if (context.now > 0L) context.now else Clock.System.now().toEpochMilliseconds()
        val now = kotlin.time.Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        val results = mutableListOf<BoardSignal>()

        results += dailyKickoffSignal(now, zone)

        triggerHourProvider().forEach { trigger ->
            val anchor = anchorSignal(now, zone, trigger) ?: return@forEach
            results += anchor
        }
        return results.take(context.limit)
    }

    private fun dailyKickoffSignal(
        now: kotlinx.datetime.LocalDateTime,
        zone: TimeZone,
    ): BoardSignal {
        val date = if (now.hour < TODAY_BOARD_DAY_CUTOFF_HOUR) {
            now.date.minus(1, DateTimeUnit.DAY)
        } else {
            now.date
        }
        val ref = "kickoff:${date.format(kotlinx.datetime.LocalDate.Formats.ISO)}"
        val signalTime = date.atTime(TODAY_BOARD_DAY_CUTOFF_HOUR, 0).toInstant(zone).toEpochMilliseconds()
        return BoardSignal(
            sourceType = BoardSignalSourceType.TIME,
            sourceRef = ref,
            title = "今日开始：${formatChineseDate(date)}",
            content = "新的一天的看板。已重置完成项，会陆续整合通知、日历、消息。",
            signalTime = signalTime,
        )
    }

    private fun anchorSignal(
        now: kotlinx.datetime.LocalDateTime,
        zone: TimeZone,
        trigger: String,
    ): BoardSignal? {
        val parsed = parseTriggerTime(trigger) ?: return null
        val anchorTime = now.date.atTime(parsed.hour, parsed.minute)
        val deltaMs = now.toInstant(zone).toEpochMilliseconds() - anchorTime.toInstant(zone).toEpochMilliseconds()
        val deltaHours = deltaMs / (60 * 60 * 1000L)
        if (deltaHours !in 0..6) return null

        val date = anchorTime.date
        val ref = "anchor:${date.format(kotlinx.datetime.LocalDate.Formats.ISO)}:$trigger"
        val phase = when (parsed.hour) {
            in 0..10 -> "上午"
            in 11..14 -> "中午"
            in 15..18 -> "下午"
            else -> "晚上"
        }
        return BoardSignal(
            sourceType = BoardSignalSourceType.TIME,
            sourceRef = ref,
            title = "$phase 看板节点：$trigger",
            content = "在 $trigger 触发的看板更新节点。",
            signalTime = anchorTime.toInstant(zone).toEpochMilliseconds(),
        )
    }

    private fun parseTriggerTime(raw: String): LocalTime? {
        val parts = raw.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime(hour, minute)
    }

    private fun formatChineseDate(date: kotlinx.datetime.LocalDate): String {
        val formatter = kotlinx.datetime.LocalDate.Format {
            monthName(
                MonthNames("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月"),
            )
            day()
            char('日')
        }
        return date.format(formatter)
    }
}
