package app.amber.feature.health

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSummaryModelsTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun aggregatesMultipleTypesIntoDailyAndWeeklyShape() {
        val summary = aggregateHealthSummary(
            records = listOf(
                HealthMetricRecord(HealthRecordType.Steps, 1_700_000_000_000, value = 1200.0),
                HealthMetricRecord(HealthRecordType.Steps, 1_700_000_100_000, value = 800.0),
                HealthMetricRecord(HealthRecordType.HeartRate, 1_700_000_200_000, value = 60.0),
                HealthMetricRecord(HealthRecordType.HeartRate, 1_700_000_300_000, value = 80.0),
                HealthMetricRecord(HealthRecordType.Sleep, 1_700_000_400_000, 1_700_003_400_000L),
                HealthMetricRecord(HealthRecordType.Weight, 1_700_000_500_000, value = 70.0),
            ),
            nowEpochMillis = 1_700_000_600_000,
            zoneId = utc,
            days = 1,
        )
        assertEquals(2000, summary.days.single().steps)
        assertEquals(70.0, summary.days.single().weightKg!!, 0.001)
        assertEquals(70.0, summary.days.single().heartRateBpm!!, 0.001)
        assertTrue(summary.days.single().sleepHours > 0)
        assertEquals(2000, summary.weekly.steps)
    }

    @Test
    fun emptyDataHasExplicitZeroDaysAndNullableMetrics() {
        val summary = aggregateHealthSummary(emptyList(), 1_700_000_000_000, utc, days = 7)
        assertEquals(7, summary.days.size)
        assertTrue(summary.days.all { it.steps == 0L && it.sleepHours == 0.0 })
        assertNull(summary.weekly.averageHeartRateBpm)
        assertNull(summary.weekly.latestWeightKg)
    }

    @Test
    fun epochNearMidnightUsesRequestedTimezone() {
        val epoch = Instant.parse("2024-01-01T23:30:00Z").toEpochMilli()
        val summary = aggregateHealthSummary(
            listOf(HealthMetricRecord(HealthRecordType.Steps, epoch, value = 3.0)),
            epoch,
            ZoneId.of("Asia/Shanghai"),
            days = 1,
        )
        assertEquals(3, summary.days.single().steps)
        assertEquals("2024-01-02", summary.days.single().date.toString())
    }

    @Test
    fun jsonMatchesIosDailyKeysAndContainsWeeklySummary() {
        val json = aggregateHealthSummary(emptyList(), 0L, utc, days = 1).toJson()
        assertTrue(json.contains("\"days\""))
        assertTrue(json.contains("\"weekly\""))
        assertTrue(json.contains("\"sleep_hours\""))
    }
}
