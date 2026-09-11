package app.amber.feature.health

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.OutcomeReceiver
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.health.connect.datatypes.Record
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One page returned by Health Connect, kept small so pagination can be tested without a device. */
internal data class HealthConnectRecordPage<T : Record>(
    val records: List<T>,
    val nextPageToken: Long,
)

private const val END_PAGE_TOKEN = -1L

/** Platform API adapter. All Health Connect references stay behind API 34 checks. */
class HealthSummaryReader(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
    private val availability: () -> HealthConnectAvailability = {
        HealthConnectAvailabilityDetector.detect(context)
    },
) {
    companion object {
        const val DEFAULT_DAYS = 7
        val READ_PERMISSIONS: Set<String> = setOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_WEIGHT",
        )

        fun authorizationIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).apply {
                addCategory(HealthConnectManager.CATEGORY_HEALTH_PERMISSIONS)
                putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }

    @Volatile
    private var authorizationState: HealthAdapterState = HealthAdapterState.AUTHORIZATION_REQUIRED

    /** Called by the Activity Result handler after the Health Connect system flow. */
    fun onAuthorizationResult(granted: Boolean) {
        authorizationState = HealthAdapterStateMachine.afterAuthorization(granted)
    }

    /** Re-check after returning from settings/Health Connect; revocation is not success. */
    fun markAuthorizationRevoked() {
        authorizationState = HealthAdapterState.AUTHORIZATION_REVOKED
    }

    fun status(): HealthAdapterStatus = when (availability()) {
        HealthConnectAvailability.AVAILABLE -> HealthAdapterStatus(
            authorizationState,
            HealthAdapterStatusText.forState(authorizationState),
        )
        HealthConnectAvailability.UNSUPPORTED_BY_OS_VERSION -> HealthAdapterStatus(
            HealthAdapterState.UNSUPPORTED,
            HealthConnectAvailability.UNSUPPORTED_BY_OS_VERSION.message,
        )
        HealthConnectAvailability.SERVICE_NOT_FOUND -> HealthAdapterStatus(
            HealthAdapterState.SERVICE_UNAVAILABLE,
            HealthConnectAvailability.SERVICE_NOT_FOUND.message,
        )
    }

    /** Reads only after the host has confirmed system authorization. */
    suspend fun readSummary(
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        days: Int = DEFAULT_DAYS,
        authorizationGranted: Boolean,
    ): Result<HealthSummary> {
        val baseStatus = status()
        if (baseStatus.state == HealthAdapterState.UNSUPPORTED || baseStatus.state == HealthAdapterState.SERVICE_UNAVAILABLE) {
            return Result.failure(HealthSummaryException(baseStatus.message, baseStatus.state))
        }
        if (!authorizationGranted || authorizationState != HealthAdapterState.READY) {
            val state = if (authorizationState == HealthAdapterState.AUTHORIZATION_DENIED) {
                HealthAdapterState.AUTHORIZATION_DENIED
            } else {
                HealthAdapterState.AUTHORIZATION_REVOKED
            }
            return Result.failure(HealthSummaryException(HealthAdapterStatusText.forState(state), state))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Result.failure(HealthSummaryException(HealthAdapterStatusText.forState(HealthAdapterState.UNSUPPORTED), HealthAdapterState.UNSUPPORTED))
        }
        val manager = context.getSystemService(HealthConnectManager::class.java)
            ?: return Result.failure(
                HealthSummaryException(
                    HealthAdapterStatusText.forState(HealthAdapterState.SERVICE_UNAVAILABLE),
                    HealthAdapterState.SERVICE_UNAVAILABLE,
                ),
            )
        return try {
            val boundedDays = days.coerceIn(1, 30)
            val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
            val start = today.minusDays((boundedDays - 1).toLong()).atStartOfDay(zoneId).toInstant()
            val end = today.plusDays(1).atStartOfDay(zoneId).toInstant()
            val range = TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()
            val records = mutableListOf<Record>().apply {
                addAll(readHealthConnectPages(StepsRecord::class.java, range) { request -> manager.readPage(request) })
                addAll(readHealthConnectPages(HeartRateRecord::class.java, range) { request -> manager.readPage(request) })
                addAll(readHealthConnectPages(SleepSessionRecord::class.java, range) { request -> manager.readPage(request) })
                addAll(readHealthConnectPages(WeightRecord::class.java, range) { request -> manager.readPage(request) })
            }
            val inputs = records.mapNotNull { it.toMetricRecord() }
            Result.success(aggregateHealthSummary(inputs, nowEpochMillis, zoneId, boundedDays))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(HealthSummaryException(
                throwable.message ?: HealthAdapterStatusText.forState(HealthAdapterState.READ_FAILED),
                HealthAdapterStateMachine.afterReadFailure(
                    throwable is HealthConnectException && throwable.errorCode == HealthConnectException.ERROR_SECURITY,
                ),
                throwable,
            ))
        }
    }

    private suspend fun <T : Record> HealthConnectManager.readPage(
        request: ReadRecordsRequestUsingFilters<T>,
    ): HealthConnectRecordPage<T> = suspendCancellableCoroutine { continuation ->
        readRecords(
            request,
            executor,
            object : OutcomeReceiver<android.health.connect.ReadRecordsResponse<T>, HealthConnectException> {
                override fun onResult(result: android.health.connect.ReadRecordsResponse<T>) {
                    if (continuation.isActive) {
                        continuation.resume(HealthConnectRecordPage(result.records, result.nextPageToken))
                    }
                }

                override fun onError(error: HealthConnectException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
    }

    private fun Record.toMetricRecord(): HealthMetricRecord? = when (this) {
        is StepsRecord -> HealthMetricRecord(HealthRecordType.Steps, startTime.toEpochMilli(), endTime.toEpochMilli(), count.toDouble())
        is HeartRateRecord -> {
            val heartRateSamples = samples
            if (heartRateSamples.isEmpty()) null else HealthMetricRecord(
                HealthRecordType.HeartRate,
                heartRateSamples.first().time.toEpochMilli(),
                heartRateSamples.last().time.toEpochMilli(),
                heartRateSamples.map { it.beatsPerMinute.toDouble() }.average(),
            )
        }
        is SleepSessionRecord -> HealthMetricRecord(HealthRecordType.Sleep, startTime.toEpochMilli(), endTime.toEpochMilli())
        is WeightRecord -> HealthMetricRecord(HealthRecordType.Weight, time.toEpochMilli(), time.toEpochMilli(), weight.inGrams / 1000.0)
        else -> null
    }
}

/** Shared production pagination loop; the callback receives each real built request. */
internal suspend fun <T : Record> readHealthConnectPages(
    type: Class<T>,
    range: android.health.connect.TimeRangeFilter,
    readPage: suspend (ReadRecordsRequestUsingFilters<T>) -> HealthConnectRecordPage<T>,
): List<T> {
    val records = mutableListOf<T>()
    var pageToken: Long? = null
    while (true) {
        val builder = ReadRecordsRequestUsingFilters.Builder(type)
            .setTimeRangeFilter(range)
            .setPageSize(1000)
        if (pageToken != null) {
            builder.setPageToken(pageToken)
        } else {
            builder.setAscending(true)
        }
        val page = readPage(builder.build())
        records += page.records
        if (page.nextPageToken == END_PAGE_TOKEN) return records
        pageToken = page.nextPageToken
    }
}

class HealthSummaryException(
    override val message: String,
    val state: HealthAdapterState,
    override val cause: Throwable? = null,
) : Exception(message, cause)

/** API-level probe with no reference to PlannedExerciseSessionRecord. */
enum class PlannedExerciseCapability {
    PLANNED_EXERCISE_SUPPORTED,
    UNSUPPORTED,
}

object PlannedExerciseFeatureProbe {
    private const val RECORD_CLASS = "android.health.connect.datatypes.PlannedExerciseSessionRecord"
    private const val PERMISSION_CLASS = "android.health.connect.HealthPermissions"
    private const val PERMISSION_FIELD = "READ_PLANNED_EXERCISE"

    fun probe(sdkInt: Int, classLoader: ClassLoader = HealthSummaryReader::class.java.classLoader!!): PlannedExerciseCapability {
        if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) return PlannedExerciseCapability.UNSUPPORTED
        return try {
            val record = Class.forName(RECORD_CLASS, false, classLoader)
            val permissions = Class.forName(PERMISSION_CLASS, false, classLoader)
            permissions.getField(PERMISSION_FIELD)
            if (record.isAssignableFrom(Record::class.java) || Record::class.java.isAssignableFrom(record)) {
                PlannedExerciseCapability.PLANNED_EXERCISE_SUPPORTED
            } else PlannedExerciseCapability.UNSUPPORTED
        } catch (_: ReflectiveOperationException) {
            PlannedExerciseCapability.UNSUPPORTED
        }
    }
}

/** Deliberately distinct from Health Connect planned exercise records. */
data class AppOwnedTrainingPlan(
    val id: String,
    val title: String,
    val scheduledAtEpochMillis: Long,
)
