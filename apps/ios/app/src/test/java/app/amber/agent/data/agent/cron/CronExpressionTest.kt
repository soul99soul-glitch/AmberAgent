package app.amber.feature.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class CronExpressionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun parsesDailySchedule() {
        val schedule = CronExpression.parse("0 9 * * *")
        val start = ZonedDateTime.of(2026, 5, 6, 8, 58, 12, 0, zone)
            .toInstant()
            .toEpochMilli()

        val next = ZonedDateTime.ofInstant(Instant.ofEpochMilli(schedule.nextRunAfter(start, zone)!!), zone)

        assertEquals(2026, next.year)
        assertEquals(5, next.monthValue)
        assertEquals(6, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(0, next.minute)
    }

    @Test
    fun parsesStepSchedule() {
        val schedule = CronExpression.parse("*/30 * * * *")
        val start = ZonedDateTime.of(2026, 5, 6, 9, 1, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        val next = ZonedDateTime.ofInstant(Instant.ofEpochMilli(schedule.nextRunAfter(start, zone)!!), zone)

        assertEquals(9, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun rejectsInvalidFieldCount() {
        val error = runCatching { CronExpression.parse("0 9 * *") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
