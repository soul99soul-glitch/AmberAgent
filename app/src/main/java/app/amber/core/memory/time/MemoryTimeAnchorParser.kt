package app.amber.core.memory.time

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object MemoryTimeAnchorParser {
    fun classifyFreshness(content: String, now: Long = System.currentTimeMillis()): MemoryFreshness {
        if (!hasFutureIntent(content) || hasHistoricalIntent(content)) {
            return MemoryFreshness.CURRENT
        }
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val currentMonth = YearMonth.from(today)
        return if (anchors(content).any { it.isBefore(today, currentMonth) }) {
            MemoryFreshness.TIME_DECAYED
        } else {
            MemoryFreshness.CURRENT
        }
    }

    fun deriveExpiresAt(content: String, now: Long = System.currentTimeMillis()): Long? {
        if (!hasFutureIntent(content) || hasHistoricalIntent(content)) return null
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val currentMonth = YearMonth.from(today)
        return anchors(content)
            .filterNot { it.isBefore(today, currentMonth) }
            .minByOrNull { it.expiresAtMillis() }
            ?.expiresAtMillis()
    }

    private fun anchors(content: String): List<MemoryTimeAnchor> =
        absoluteDateRegex.findAll(content).mapNotNull { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val month = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val day = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            MemoryTimeAnchor.from(year, month, day)
        }.toList() + chineseDateRegex.findAll(content).mapNotNull { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val month = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val day = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            MemoryTimeAnchor.from(year, month, day)
        }.toList()

    private fun hasFutureIntent(content: String): Boolean {
        val lower = content.lowercase()
        val chineseFuture = listOf(
            "计划", "打算", "准备", "要去", "将去", "行程", "旅行安排", "出差安排", "旅行",
        )
        return chineseFuture.any { it in lower } || englishFutureRegex.containsMatchIn(lower)
    }

    private fun hasHistoricalIntent(content: String): Boolean {
        val lower = content.lowercase()
        val hints = listOf(
            "去过", "去了", "已去", "已经去", "回来", "结束",
            "visited", "went to", "traveled to", "have been", "has been",
        )
        return hints.any { it in lower }
    }

    private val absoluteDateRegex = Regex("""\b((?:19|20)\d{2})-(\d{1,2})(?:-(\d{1,2}))?\b""")
    private val chineseDateRegex = Regex("""((?:19|20)\d{2})年(\d{1,2})月(?:(\d{1,2})日)?""")
    private val englishFutureRegex =
        Regex("""\b(?:will|plan|plans|planned|planning|going\s+to|trip|travel|visit)\b""")
}

enum class MemoryFreshness {
    CURRENT,
    TIME_DECAYED,
}

private data class MemoryTimeAnchor(
    val date: LocalDate?,
    val month: YearMonth,
) {
    fun isBefore(today: LocalDate, currentMonth: YearMonth): Boolean =
        date?.isBefore(today) ?: month.isBefore(currentMonth)

    fun expiresAtMillis(): Long {
        val expiresAt = date?.plusDays(1)?.atStartOfDay()
            ?: month.plusMonths(1).atDay(1).atStartOfDay()
        return expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        fun from(year: Int, month: Int, day: Int?): MemoryTimeAnchor? =
            runCatching {
                if (day != null) {
                    val date = LocalDate.of(year, month, day)
                    MemoryTimeAnchor(date = date, month = YearMonth.from(date))
                } else {
                    MemoryTimeAnchor(date = null, month = YearMonth.of(year, month))
                }
            }.getOrNull()
    }
}
