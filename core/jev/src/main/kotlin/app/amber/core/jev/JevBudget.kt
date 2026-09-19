package app.amber.core.jev

import kotlinx.serialization.Serializable

/** 日用量的持久化接口；app 层用 SharedPreferences 实现，core 层零 Android 依赖。 */
interface JevUsageStore {
    fun load(dayKey: String): JevDailyUsage

    fun store(dayKey: String, usage: JevDailyUsage)

    companion object {
        /** 纯内存实现（测试/无注入场景）；进程重启即重置。 */
        val IN_MEMORY: JevUsageStore = object : JevUsageStore {
            private val map = HashMap<String, JevDailyUsage>()

            @Synchronized
            override fun load(dayKey: String): JevDailyUsage = map[dayKey] ?: JevDailyUsage()

            @Synchronized
            override fun store(dayKey: String, usage: JevDailyUsage) {
                map[dayKey] = usage
            }
        }
    }
}

@Serializable
data class JevDailyUsage(val requests: Int = 0, val bodyBytes: Long = 0)

/**
 * 出站预算：单 run（用户轮）共享预算 + App 日预算。重试按出站次数计数；
 * 本地预算是软上限，不冒充供应商或跨设备硬账单。
 */
class JevBudget(
    private val usageStore: JevUsageStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Screen automation needs more turns than ordinary Jev purposes. */
        const val SCREEN_AUTOMATION_MAX_REQUESTS = 24
        const val SCREEN_AUTOMATION_MAX_STATE_BYTES = 512 * 1024
    }

    private data class PurposeUsage(var requests: Int = 0, var stateBytes: Long = 0)

    /**
     * [requests] and [stateBytes] remain the aggregate run totals for callers
     * and diagnostics. The private buckets enforce purpose-specific ceilings
     * without inventing a second run key for screen automation.
     */
    data class RunUsage(var requests: Int = 0, var stateBytes: Long = 0) {
        private val purposeUsages = HashMap<JevPurpose?, PurposeUsage>()

        private fun bucket(purpose: JevPurpose?): JevPurpose? =
            purpose.takeIf { it == JevPurpose.SCREEN_AUTOMATION }

        fun canConsume(purpose: JevPurpose?, requestCount: Int, bodyBytes: Long): Boolean {
            val usage = purposeUsages[bucket(purpose)] ?: PurposeUsage()
            val requestLimit = if (bucket(purpose) == JevPurpose.SCREEN_AUTOMATION) {
                SCREEN_AUTOMATION_MAX_REQUESTS
            } else {
                JevLimits.PER_RUN_MAX_REQUESTS
            }
            val stateLimit = if (bucket(purpose) == JevPurpose.SCREEN_AUTOMATION) {
                SCREEN_AUTOMATION_MAX_STATE_BYTES.toLong()
            } else {
                JevLimits.PER_RUN_MAX_STATE_BYTES.toLong()
            }
            return usage.requests + requestCount <= requestLimit &&
                usage.stateBytes + bodyBytes <= stateLimit
        }

        fun record(purpose: JevPurpose?, requestCount: Int, bodyBytes: Long) {
            val usage = purposeUsages.getOrPut(bucket(purpose)) { PurposeUsage() }
            usage.requests += requestCount
            usage.stateBytes += bodyBytes
            requests += requestCount
            stateBytes += bodyBytes
        }
    }

    private val runUsages = object : LinkedHashMap<String, RunUsage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RunUsage>): Boolean =
            size > 128
    }

    @Synchronized
    fun consume(
        runKey: String?,
        requestCount: Int = 1,
        bodyBytes: Long,
        purpose: JevPurpose? = null,
    ): Boolean {
        val dayKey = dayKey(clock())
        val daily = usageStore.load(dayKey)
        if (daily.requests + requestCount > JevLimits.DAILY_MAX_REQUESTS) return false
        if (daily.bodyBytes + bodyBytes > JevLimits.DAILY_MAX_BODY_BYTES) return false
        if (runKey != null) {
            val run = runUsages[runKey] ?: RunUsage().also { runUsages[runKey] = it }
            if (!run.canConsume(purpose, requestCount, bodyBytes)) return false
            run.record(purpose, requestCount, bodyBytes)
        }
        usageStore.store(dayKey, daily.copy(requests = daily.requests + requestCount, bodyBytes = daily.bodyBytes + bodyBytes))
        return true
    }

    @Synchronized
    fun dailyUsage(): JevDailyUsage = usageStore.load(dayKey(clock()))

    @Synchronized
    fun runUsage(runKey: String): RunUsage = runUsages[runKey] ?: RunUsage()

    private fun dayKey(millis: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
}
