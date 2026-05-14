package me.rerere.rikkahub.data.agent.board.collector

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Collects today's app usage data from [UsageStatsManager]. Requires the user to
 * grant `android.permission.PACKAGE_USAGE_STATS` via Settings → Apps → Special Access
 * → Usage Access.
 *
 * Returns a sorted list of [AppUsageEntry] (most-used first) with human-readable app
 * labels and formatted durations. System UI / launcher / keyboard packages are filtered
 * out to focus on meaningful user activity.
 *
 * This collector does NOT implement [BoardSignalCollector] — it's consumed directly by
 * [DailyReviewAgent] rather than going through the signal aggregator pipeline, because
 * usage stats are retrospective snapshots (not event signals) and would be awkward to
 * dedup/score through the standard flow.
 */
class AppUsageCollector(private val context: Context) {

    /**
     * Collect app usage for the current day up to [untilMs].
     * Returns an empty list if the usage stats permission is not granted.
     */
    suspend fun collectToday(untilMs: Long = System.currentTimeMillis()): List<AppUsageEntry> =
        withContext(Dispatchers.IO) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@withContext emptyList()

            val todayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val stats = runCatching {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, todayStart, untilMs)
            }.getOrNull().orEmpty()

            stats
                .filter { it.totalTimeInForeground > MIN_FOREGROUND_MS }
                .filterNot { isSystemPackage(it.packageName) }
                .sortedByDescending { it.totalTimeInForeground }
                .take(MAX_APPS)
                .map { stat ->
                    AppUsageEntry(
                        packageName = stat.packageName,
                        appLabel = resolveAppLabel(stat.packageName),
                        foregroundMinutes = (stat.totalTimeInForeground / 60_000L).toInt(),
                        lastUsed = stat.lastTimeUsed,
                    )
                }
        }

    private fun resolveAppLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    companion object {
        /** Ignore apps used less than 1 minute in foreground. */
        private const val MIN_FOREGROUND_MS = 60_000L

        /** Cap the list to avoid bloating the LLM prompt. */
        private const val MAX_APPS = 20

        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.inputmethod.latin",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.oppo.launcher",
            "com.android.providers.downloads",
            "com.android.settings",
            "com.android.vending",
        )

        private val SYSTEM_PREFIXES = listOf(
            "com.android.internal",
            "com.android.providers",
            "com.qualcomm.",
            "com.miui.securitycenter",
        )

        private fun isSystemPackage(pkg: String): Boolean {
            if (pkg in SYSTEM_PACKAGES) return true
            return SYSTEM_PREFIXES.any { pkg.startsWith(it) }
        }
    }
}

data class AppUsageEntry(
    val packageName: String,
    val appLabel: String,
    val foregroundMinutes: Int,
    val lastUsed: Long,
) {
    fun formattedDuration(): String = when {
        foregroundMinutes >= 60 -> "${foregroundMinutes / 60}小时${foregroundMinutes % 60}分钟"
        else -> "${foregroundMinutes}分钟"
    }
}
