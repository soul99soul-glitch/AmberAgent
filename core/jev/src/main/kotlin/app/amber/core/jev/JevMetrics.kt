package app.amber.core.jev

/** 单条判断记录：只存用途/版本/大小/耗时/回退等元数据，不存业务原文。 */
data class JevMetricEntry(
    val timestamp: Long,
    val purpose: JevPurpose,
    val mode: JevMode,
    val outcome: String,
    val latencyMs: Long,
    val requestBytes: Int,
    val usage: JevUsage?,
    val model: String?,
) {
    companion object {
        const val OUTCOME_APPLIED = "applied"
        const val OUTCOME_SHADOW = "shadow"
        const val OUTCOME_CACHE_HIT = "cache_hit"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_SKIPPED = "skipped"
    }
}

/** 有界环形记录，供设置页"近期开销/状态"展示；默认容量 128。 */
class JevMetrics(private val capacity: Int = 128) {

    private val entries = ArrayDeque<JevMetricEntry>(capacity)

    @Synchronized
    fun record(entry: JevMetricEntry) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<JevMetricEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    data class Summary(
        val total: Int,
        val applied: Int,
        val shadow: Int,
        val cacheHits: Int,
        val fallbacks: Int,
        val failures: Int,
        val lastOutcome: String?,
    )

    @Synchronized
    fun summary(): Summary {
        val list = entries.toList()
        return Summary(
            total = list.size,
            applied = list.count { it.outcome == JevMetricEntry.OUTCOME_APPLIED },
            shadow = list.count { it.outcome == JevMetricEntry.OUTCOME_SHADOW },
            cacheHits = list.count { it.outcome == JevMetricEntry.OUTCOME_CACHE_HIT },
            fallbacks = list.count { it.outcome.startsWith("fallback") },
            failures = list.count { it.outcome == JevMetricEntry.OUTCOME_FAILED },
            lastOutcome = list.lastOrNull()?.outcome,
        )
    }
}
