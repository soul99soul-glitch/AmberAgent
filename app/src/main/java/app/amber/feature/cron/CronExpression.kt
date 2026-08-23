package app.amber.feature.cron

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class CronExpression(
    val raw: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val dayOfMonthWildcard: Boolean,
    private val dayOfWeekWildcard: Boolean,
) {
    fun nextRunAfter(epochMs: Long, zoneId: ZoneId): Long? {
        var cursor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zoneId)
            .plusMinutes(1)
            .truncatedTo(ChronoUnit.MINUTES)
        val deadline = cursor.plusDays(MAX_LOOKAHEAD_DAYS)
        while (!cursor.isAfter(deadline)) {
            if (matches(cursor)) {
                return cursor.toInstant().toEpochMilli()
            }
            cursor = cursor.plusMinutes(1)
        }
        return null
    }

    private fun matches(dateTime: ZonedDateTime): Boolean {
        if (dateTime.minute !in minutes) return false
        if (dateTime.hour !in hours) return false
        if (dateTime.monthValue !in months) return false

        val dayOfMonthMatches = dateTime.dayOfMonth in daysOfMonth
        val dayOfWeekValue = dateTime.dayOfWeek.value
        val dayOfWeekMatches = dayOfWeekValue in daysOfWeek
        val dayMatches = if (!dayOfMonthWildcard && !dayOfWeekWildcard) {
            dayOfMonthMatches || dayOfWeekMatches
        } else {
            dayOfMonthMatches && dayOfWeekMatches
        }
        return dayMatches
    }

    companion object {
        private const val MAX_LOOKAHEAD_DAYS = 366L

        fun parse(expression: String): CronExpression {
            val parts = expression.trim().split(Regex("\\s+"))
            require(parts.size == 5) {
                "Cron expression must have 5 fields: minute hour day-of-month month day-of-week"
            }
            val domWildcard = parts[2] == "*"
            val dowWildcard = parts[4] == "*"
            return CronExpression(
                raw = expression.trim(),
                minutes = parseField(parts[0], 0, 59),
                hours = parseField(parts[1], 0, 23),
                daysOfMonth = parseField(parts[2], 1, 31),
                months = parseField(parts[3], 1, 12),
                daysOfWeek = parseField(parts[4], 0, 7).map { if (it == 0) 7 else it }.toSet(),
                dayOfMonthWildcard = domWildcard,
                dayOfWeekWildcard = dowWildcard,
            )
        }

        private fun parseField(field: String, min: Int, max: Int): Set<Int> {
            require(field.isNotBlank()) { "Cron field cannot be blank" }
            return field.split(",")
                .flatMap { parseSegment(it.trim(), min, max) }
                .toSortedSet()
                .also { require(it.isNotEmpty()) { "Cron field '$field' produced no values" } }
        }

        private fun parseSegment(segment: String, min: Int, max: Int): List<Int> {
            require(segment.isNotBlank()) { "Cron segment cannot be blank" }
            val rangePart = segment.substringBefore("/")
            val step = segment.substringAfter("/", "1").toIntOrNull()
                ?: throw IllegalArgumentException("Invalid cron step in '$segment'")
            require(step > 0) { "Cron step must be greater than 0 in '$segment'" }

            val (start, end) = when {
                rangePart == "*" -> min to max
                "-" in rangePart -> {
                    val start = rangePart.substringBefore("-").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid cron range start in '$segment'")
                    val end = rangePart.substringAfter("-").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid cron range end in '$segment'")
                    start to end
                }
                else -> {
                    val value = rangePart.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid cron value in '$segment'")
                    value to value
                }
            }
            require(start in min..max && end in min..max && start <= end) {
                "Cron segment '$segment' is outside $min..$max"
            }
            return (start..end step step).toList()
        }
    }
}
