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
    data class RunUsage(var requests: Int = 0, var stateBytes: Long = 0)

    private val runUsages = object : LinkedHashMap<String, RunUsage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RunUsage>): Boolean =
            size > 128
    }

    @Synchronized
    fun consume(runKey: String?, requestCount: Int = 1, bodyBytes: Long): Boolean {
        val dayKey = dayKey(clock())
        val daily = usageStore.load(dayKey)
        if (daily.requests + requestCount > JevLimits.DAILY_MAX_REQUESTS) return false
        if (daily.bodyBytes + bodyBytes > JevLimits.DAILY_MAX_BODY_BYTES) return false
        if (runKey != null) {
            val run = runUsages[runKey] ?: RunUsage().also { runUsages[runKey] = it }
            if (run.requests + requestCount > JevLimits.PER_RUN_MAX_REQUESTS) return false
            if (run.stateBytes + bodyBytes > JevLimits.PER_RUN_MAX_STATE_BYTES) return false
            run.requests += requestCount
            run.stateBytes += bodyBytes
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
