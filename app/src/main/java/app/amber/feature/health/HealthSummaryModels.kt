package app.amber.feature.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The four Health Connect record families in the first read-only slice. */
enum class HealthRecordType {
    Steps,
    HeartRate,
    Sleep,
    Weight,
}

/** Platform-neutral input used by the reducer and JVM tests. */
data class HealthMetricRecord(
    val type: HealthRecordType,
    val startEpochMillis: Long,
    val endEpochMillis: Long = startEpochMillis,
    /** steps, bpm, or kilograms; Sleep uses the interval for its duration. */
    val value: Double = 0.0,
)

data class HealthDailySummary(
    val date: LocalDate,
    val steps: Long = 0,
    val heartRateBpm: Double? = null,
    val sleepHours: Double = 0.0,
    val weightKg: Double? = null,
)

data class HealthWeeklySummary(
    val steps: Long = 0,
    val averageHeartRateBpm: Double? = null,
    val sleepHours: Double = 0.0,
    val latestWeightKg: Double? = null,
)

data class HealthSummary(
    val days: List<HealthDailySummary>,
    val weekly: HealthWeeklySummary,
) {
    fun toJson(): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        return buildJsonObject {
            put("ok", true)
            put("tool", "health_summary")
            put("privacy", "user_authorized_health_data")
            putJsonArray("days") {
                days.forEach { day ->
                    add(buildJsonObject {
                        put("date", day.date.format(formatter))
                        put("steps", day.steps)
                        day.heartRateBpm?.let { put("heart_rate_bpm", it) }
                        put("sleep_hours", day.sleepHours)
                        day.weightKg?.let { put("weight_kg", it) }
                    })
                }
            }
            putJsonObject("weekly") {
                put("steps", weekly.steps)
                weekly.averageHeartRateBpm?.let { put("average_heart_rate_bpm", it) }
                put("sleep_hours", weekly.sleepHours)
                weekly.latestWeightKg?.let { put("latest_weight_kg", it) }
            }
        }.toString()
    }
}

/**
 * Deterministic day/week reducer. Records are bucketed in [zoneId], never in
 * the device default zone, so an epoch at a local midnight is testable.
 */
fun aggregateHealthSummary(
    records: List<HealthMetricRecord>,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    days: Int = 7,
): HealthSummary {
    val boundedDays = days.coerceIn(1, 30)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val first = today.minusDays((boundedDays - 1).toLong())
    val dates = (0 until boundedDays).map { first.plusDays(it.toLong()) }
    val byDate = dates.associateWith { date -> DayAccumulator(date) }.toMutableMap()

    records.forEach { record ->
        val date = Instant.ofEpochMilli(record.startEpochMillis).atZone(zoneId).toLocalDate()
        val accumulator = byDate[date] ?: return@forEach
        when (record.type) {
            HealthRecordType.Steps -> accumulator.steps += record.value.coerceAtLeast(0.0)
            HealthRecordType.HeartRate -> if (record.value > 0.0) {
                accumulator.heartRates += record.value
            }
            HealthRecordType.Sleep -> {
                val end = maxOf(record.endEpochMillis, record.startEpochMillis)
                val startOfDay = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                val overlap = (minOf(end, endOfDay) - maxOf(record.startEpochMillis, startOfDay)).coerceAtLeast(0L)
                accumulator.sleepHours += overlap / 3_600_000.0
            }
            HealthRecordType.Weight -> if (record.value > 0.0) {
                if (accumulator.latestWeightTime == null || record.startEpochMillis >= accumulator.latestWeightTime!!) {
                    accumulator.latestWeightTime = record.startEpochMillis
                    accumulator.weightKg = record.value
                }
            }
        }
    }

    val summaries = dates.map { date -> byDate.getValue(date).toSummary() }
    val heartRates = summaries.mapNotNull { it.heartRateBpm }
    return HealthSummary(
        days = summaries,
        weekly = HealthWeeklySummary(
            steps = summaries.sumOf { it.steps },
            averageHeartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average(),
            sleepHours = summaries.sumOf { it.sleepHours },
            latestWeightKg = summaries.asReversed().firstNotNullOfOrNull { it.weightKg },
        ),
    )
}

private class DayAccumulator(private val date: LocalDate) {
    var steps = 0.0
    val heartRates = mutableListOf<Double>()
    var sleepHours = 0.0
    var weightKg: Double? = null
    var latestWeightTime: Long? = null

    fun toSummary() = HealthDailySummary(
        date = date,
        steps = steps.toLong(),
        heartRateBpm = heartRates.takeIf { it.isNotEmpty() }?.average(),
        sleepHours = sleepHours,
        weightKg = weightKg,
    )
}
