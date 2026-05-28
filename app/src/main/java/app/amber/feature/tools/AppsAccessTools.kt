package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool

internal fun createAppsListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "apps_list",
    description = "List launchable apps visible to AmberAgent without requesting QUERY_ALL_PACKAGES.",
    parameters = {
        obj(
            "query" to accessStringProp("Optional app label or package filter."),
            "limit" to integerProp("Maximum apps. Defaults to 80."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("apps_list", "列出应用", "apps", input.safePreview()) {
            textJson {
                put("apps", queryLaunchableApps(context, input.string("query").orEmpty(), input.limit(default = 80, max = 200)))
            }
        }
    }
)

internal fun createAppOpenTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "app_open",
    description = "Open an installed launchable app by package name.",
    parameters = {
        obj(
            "package_name" to accessStringProp("Android package name to launch."),
            required = listOf("package_name")
        )
    },
    needsApproval = true,
    execute = { input ->
        deps.trackSystemTool("app_open", "打开应用", "apps", input) {
            val packageName = input.requiredString("package_name")
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: error("No launch intent for package: $packageName")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            textJson {
                put("success", true)
                put("package_name", packageName)
            }
        }
    }
)

internal fun createAppsInstalledListTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "apps_installed_list",
    description = "List installed packages visible to AmberAgent. Debug/advanced experiment path; QUERY_ALL_PACKAGES may be restricted by Google Play policy.",
    parameters = {
        obj(
            "query" to accessStringProp("Optional app label or package filter."),
            "include_system" to booleanProp("Include system apps. Defaults to false."),
            "include_permissions" to booleanProp("Include declared permissions. Defaults to false."),
            "limit" to integerProp("Maximum packages. Defaults to 200."),
        )
    },
    execute = { input ->
        deps.trackSystemTool("apps_installed_list", "读取全量应用列表", "installed_apps_full_access", input.safePreview()) {
            textJson {
                put(
                    "apps",
                    queryInstalledApps(
                        context = context,
                        query = input.string("query").orEmpty(),
                        includeSystem = input.boolean("include_system") ?: false,
                        includePermissions = input.boolean("include_permissions") ?: false,
                        limit = input.limit(default = 200, max = 1000),
                    )
                )
                put("note", "This uses Android package visibility. Some devices or Play builds may still limit results.")
            }
        }
    }
)

internal fun createAppInfoTool(context: Context, deps: SystemAccessDeps): Tool = Tool(
    name = "app_info",
    description = "Return label, version, launchability, install source, and optional declared permissions for a package.",
    parameters = {
        obj(
            "package_name" to accessStringProp("Android package name."),
            "include_permissions" to booleanProp("Include declared permissions. Defaults to false."),
            required = listOf("package_name")
        )
    },
    execute = { input ->
        deps.trackSystemTool("app_info", "读取应用信息", "apps", input) {
            textJson {
                put("app", packageInfoJson(context, input.requiredString("package_name"), input.boolean("include_permissions") ?: false))
            }
        }
    }
)

private fun queryLaunchableApps(context: Context, query: String, limit: Int) = buildJsonArray {
    val pm = context.packageManager
    val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(launchIntent, 0)
        .map { resolve ->
            val packageName = resolve.activityInfo.packageName
            val label = resolve.loadLabel(pm).toString()
            packageName to label
        }
        .filter { (packageName, label) ->
            query.isBlank() ||
                packageName.contains(query, ignoreCase = true) ||
                label.contains(query, ignoreCase = true)
        }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
        .take(limit)
        .forEach { (packageName, label) ->
            add(buildJsonObject {
                put("package_name", packageName)
                put("label", label)
            })
        }
}

private fun queryInstalledApps(context: Context, query: String, includeSystem: Boolean, includePermissions: Boolean, limit: Int): JsonElement = buildJsonArray {
    val pm = context.packageManager
    val flags = if (includePermissions) PackageManager.GET_PERMISSIONS else 0
    @Suppress("DEPRECATION")
    pm.getInstalledPackages(flags)
        .asSequence()
        .filter { it.applicationInfo != null }
        .filter { info ->
            val appInfo = info.applicationInfo ?: return@filter false
            includeSystem || (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }
        .filter { info ->
            val appInfo = info.applicationInfo ?: return@filter false
            val label = appInfo.loadLabel(pm).toString()
            query.isBlank() ||
                info.packageName.contains(query, ignoreCase = true) ||
                label.contains(query, ignoreCase = true)
        }
        .sortedBy { it.applicationInfo?.loadLabel(pm).toString().lowercase() }
        .take(limit)
        .forEach { info ->
            add(packageInfoJson(context, info.packageName, includePermissions))
        }
}

private fun packageInfoJson(context: Context, packageName: String, includePermissions: Boolean) = buildJsonObject {
    val pm = context.packageManager
    val flags = if (includePermissions) PackageManager.GET_PERMISSIONS else 0
    @Suppress("DEPRECATION")
    val info = pm.getPackageInfo(packageName, flags)
    val appInfo = info.applicationInfo ?: pm.getApplicationInfo(packageName, 0)
    put("package_name", packageName)
    put("label", appInfo.loadLabel(pm).toString())
    put("version_name", info.versionName.orEmpty())
    put("version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
    put("system_app", (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
    put("launchable", pm.getLaunchIntentForPackage(packageName) != null)
    put("first_install_time_epoch_ms", info.firstInstallTime)
    put("last_update_time_epoch_ms", info.lastUpdateTime)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        put("install_source", runCatching { pm.getInstallSourceInfo(packageName).installingPackageName.orEmpty() }.getOrDefault(""))
    } else {
        @Suppress("DEPRECATION")
        put("install_source", pm.getInstallerPackageName(packageName).orEmpty())
    }
    if (includePermissions) {
        put("requested_permissions", buildJsonArray {
            info.requestedPermissions.orEmpty().forEach { permission -> add(permission) }
        })
    }
}
