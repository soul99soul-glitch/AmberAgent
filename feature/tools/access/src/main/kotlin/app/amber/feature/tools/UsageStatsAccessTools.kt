package app.amber.feature.tools

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.Tool

fun createUsageStatsListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "usage_stats_list",
    description = "List recent app usage stats after Usage Access is enabled.",
    parameters = {
        obj(
            "since_epoch_ms" to integerProp("Start Unix epoch millis. Defaults to 24 hours ago."),
            "limit" to integerProp("Maximum apps. Defaults to 30."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("usage_stats_list", "读取应用使用情况", "usage_access", input.safePreview()) {
            textJson {
                put("usage_stats", queryUsageStats(context, input.long("since_epoch_ms"), input.limit(default = 30, max = 100)))
            }
        }
    }
)

private fun queryUsageStats(context: Context, since: Long?, limit: Int) = buildJsonArray {
    val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
        ?: error("UsageStatsManager is unavailable")
    val end = System.currentTimeMillis()
    val start = since ?: (end - 24L * 60L * 60L * 1000L)
    usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        .filter { it.lastTimeUsed > 0 }
        .sortedByDescending { it.lastTimeUsed }
        .take(limit)
        .forEach { usage ->
            add(buildJsonObject {
                put("package_name", usage.packageName)
                put("label", appLabel(context, usage.packageName))
                put("last_time_used_epoch_ms", usage.lastTimeUsed)
                put("total_time_foreground_ms", usage.totalTimeInForeground)
            })
        }
}

private fun appLabel(context: Context, packageName: String): String {
    val pm = context.packageManager
    return runCatching {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrDefault("")
}
