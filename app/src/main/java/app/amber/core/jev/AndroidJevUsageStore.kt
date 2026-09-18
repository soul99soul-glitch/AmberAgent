package app.amber.core.jev

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日预算计数器的 SharedPreferences 持久化；仅存数字，无任何凭据/原文。 */
class AndroidJevUsageStore(context: Context) : JevUsageStore {
    private val prefs = context.applicationContext.getSharedPreferences("jev_usage", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun load(dayKey: String): JevDailyUsage {
        val raw = prefs.getString(dayKey, null) ?: return JevDailyUsage()
        val parts = raw.split('|')
        val requests = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val bytes = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return JevDailyUsage(requests, bytes)
    }

    override fun store(dayKey: String, usage: JevDailyUsage) {
        prefs.edit()
            .putString(dayKey, "${usage.requests}|${usage.bodyBytes}")
            .apply()
        prune(dayKey)
    }

    /** 只保留最近 3 天的键，避免无限增长。 */
    private fun prune(activeDayKey: String) {
        val keys = prefs.all.keys.filter { it != activeDayKey }
        if (keys.size <= 3) return
        val sorted = keys.sortedDescending()
        val toRemove = sorted.drop(2)
        if (toRemove.isEmpty()) return
        prefs.edit().apply { toRemove.forEach(::remove) }.apply()
    }

    internal fun formatDay(millis: Long): String = dayFormat.format(Date(millis))
}
