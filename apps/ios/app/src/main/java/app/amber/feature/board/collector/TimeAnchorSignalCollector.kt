package app.amber.feature.board.collector

import app.amber.feature.board.BoardSignalSourceType
import app.amber.feature.board.TODAY_BOARD_DAY_CUTOFF_HOUR
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Emits "anchor" signals tied to time-of-day, so the agent always has at least something
 * to react to even on a quiet day. Two kinds of anchors:
 *
 * 1. **Daily kickoff** at the cutoff hour ([TODAY_BOARD_DAY_CUTOFF_HOUR], usually 04:00).
 *    Acts as the seed for "what's planned for today" if no other signals exist.
 * 2. **Trigger-time markers**: one signal per scheduler run, useful for the agent to
 *    distinguish morning / noon / evening summaries.
 *
 * These are intentionally low-weight — they should never displace real signals; they
 * just guarantee the agent has *something* to anchor on.
 */
class TimeAnchorSignalCollector(
    private val triggerHourProvider: () -> List<String> = { listOf("08:00", "12:00", "18:00") },
) : BoardSignalCollector {
    override val sourceType: String = BoardSignalSourceType.TIME

    override suspend fun collect(limit: Int): List<RawBoardSignal> {
        val now = ZonedDateTime.now()
        val results = mutableListOf<RawBoardSignal>()

        results += dailyKickoffSignal(now)

        triggerHourProvider().forEach { trigger ->
            val anchor = anchorSignal(now, trigger) ?: return@forEach
            results += anchor
        }
        return results.take(limit)
    }

    private fun dailyKickoffSignal(now: ZonedDateTime): RawBoardSignal {
        val date = if (now.hour < TODAY_BOARD_DAY_CUTOFF_HOUR) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
        val ref = "kickoff:${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}"
        val signalTime = date.atTime(TODAY_BOARD_DAY_CUTOFF_HOUR, 0)
            .atZone(now.zone)
            .toInstant()
            .toEpochMilli()
        return RawBoardSignal(
            sourceType = BoardSignalSourceType.TIME,
            sourceRef = ref,
            title = "今日开始：${date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINESE))}",
            content = "新的一天的看板。已重置完成项，会陆续整合通知、日历、消息。",
            signalTime = signalTime,
        )
    }

    private fun anchorSignal(now: ZonedDateTime, trigger: String): RawBoardSignal? {
        val parsed = runCatching { LocalTime.parse(trigger) }.getOrNull() ?: return null
        // Only emit anchors that are within the past 6 hours so we don't spam tomorrow's
        // entries with yesterday's trigger times after a late-night run.
        val anchorTime = now.toLocalDate().atTime(parsed).atZone(now.zone)
        val deltaHours = java.time.Duration.between(anchorTime, now).toHours()
        if (deltaHours !in 0..6) return null

        val date = anchorTime.toLocalDate()
        val ref = "anchor:${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}:$trigger"
        val phase = when (parsed.hour) {
            in 0..10 -> "上午"
            in 11..14 -> "中午"
            in 15..18 -> "下午"
            else -> "晚上"
        }
        return RawBoardSignal(
            sourceType = BoardSignalSourceType.TIME,
            sourceRef = ref,
            title = "$phase 看板节点：$trigger",
            content = "在 $trigger 触发的看板更新节点。",
            signalTime = anchorTime.toInstant().toEpochMilli(),
        )
    }
}
