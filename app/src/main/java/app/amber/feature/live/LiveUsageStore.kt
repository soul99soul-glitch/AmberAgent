package app.amber.feature.live

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.liveUsageDataStore by preferencesDataStore(name = "live_usage")

/**
 * 伴随分析的用量累计（蓝图 v3 §7.2 P0-4 / §6 Room 行"统计保留"）：
 * agent_event 有 TTL 清理，usage 总额必须独立于事件留存——这里保存的是
 * "清理前汇总"的只增计数，删除事件不影响统计完整性。
 */
class LiveUsageStore(private val context: Context) {

    data class Totals(
        val promptTokens: Long = 0L,
        val completionTokens: Long = 0L,
        val cachedTokens: Long = 0L,
        val analysisCount: Long = 0L,
        val firstAtMillis: Long = 0L,
    )

    val totals: Flow<Totals> = context.liveUsageDataStore.data.map { p ->
        Totals(
            promptTokens = p[KEY_PROMPT] ?: 0L,
            completionTokens = p[KEY_COMPLETION] ?: 0L,
            cachedTokens = p[KEY_CACHED] ?: 0L,
            analysisCount = p[KEY_COUNT] ?: 0L,
            firstAtMillis = p[KEY_FIRST_AT] ?: 0L,
        )
    }

    suspend fun accumulate(promptTokens: Int, completionTokens: Int, cachedTokens: Int) {
        context.liveUsageDataStore.edit { p ->
            if ((p[KEY_FIRST_AT] ?: 0L) == 0L) p[KEY_FIRST_AT] = System.currentTimeMillis()
            p[KEY_PROMPT] = (p[KEY_PROMPT] ?: 0L) + promptTokens
            p[KEY_COMPLETION] = (p[KEY_COMPLETION] ?: 0L) + completionTokens
            p[KEY_CACHED] = (p[KEY_CACHED] ?: 0L) + cachedTokens
            p[KEY_COUNT] = (p[KEY_COUNT] ?: 0L) + 1
        }
    }

    private companion object {
        val KEY_PROMPT = longPreferencesKey("prompt_tokens")
        val KEY_COMPLETION = longPreferencesKey("completion_tokens")
        val KEY_CACHED = longPreferencesKey("cached_tokens")
        val KEY_COUNT = longPreferencesKey("analysis_count")
        val KEY_FIRST_AT = longPreferencesKey("first_at_millis")
    }
}
