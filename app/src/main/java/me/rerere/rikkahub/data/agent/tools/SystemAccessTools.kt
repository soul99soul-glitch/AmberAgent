package me.rerere.rikkahub.data.agent.tools

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.io.File

class SystemAccessTools(
    private val context: Context,
    private val permissionBroker: AgentPermissionBroker,
    private val activityStore: AgentToolActivityStore,
    private val workspaceManager: WorkspaceManager,
) {
    private val deps = SystemAccessDeps(activityStore, permissionBroker)

    fun getTools(): List<Tool> = listOf(
        contactsSearchTool,
        contactsWriteTool,
        smsListTool,
        smsReadTool,
        smsSendTool,
        devicePhoneStateTool,
        callLogListTool,
        callPhoneTool,
        calendarListTool,
        calendarCreateTool,
        mediaSearchTool,
        locationCurrentTool,
        audioRecordOnceTool,
        notificationListTool,
        usageStatsListTool,
        appsListTool,
        appsInstalledListTool,
        appInfoTool,
        appOpenTool,
        batteryStatusTool,
        networkStatusTool,
        wifiStatusTool,
        deviceInfoTool,
        settingsOpenTool,
        intentOpenTool,
        shareTextTool,
        shareFileTool,
        notificationPostTool,
    )

    private val contactsSearchTool by lazy { createContactsSearchTool(context, deps) }
    private val contactsWriteTool by lazy { createContactsWriteTool(context, deps) }

    private val smsListTool by lazy { createSmsListTool(context, deps) }
    private val smsReadTool by lazy { createSmsReadTool(context, deps) }
    private val smsSendTool by lazy { createSmsSendTool(context, deps) }

    private val devicePhoneStateTool by lazy { createDevicePhoneStateTool(context, deps) }
    private val callLogListTool by lazy { createCallLogListTool(context, deps) }
    private val callPhoneTool by lazy { createCallPhoneTool(context, deps) }

    private val calendarListTool by lazy { createCalendarListTool(context, deps) }
    private val calendarCreateTool by lazy { createCalendarCreateTool(context, deps) }

    private val mediaSearchTool by lazy { createMediaSearchTool(context, deps) }
    private val locationCurrentTool by lazy { createLocationCurrentTool(context, deps) }
    private val audioRecordOnceTool by lazy { createAudioRecordOnceTool(context, deps) }
    private val notificationListTool by lazy { createNotificationListTool(deps) }
    private val usageStatsListTool by lazy { createUsageStatsListTool(context, deps) }

    private val appsListTool = Tool(
        name = "apps_list",
        description = "List launchable apps visible to AmberAgent without requesting QUERY_ALL_PACKAGES.",
        parameters = {
            obj(
                "query" to stringProp("Optional app label or package filter."),
                "limit" to integerProp("Maximum apps. Defaults to 80."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("apps_list", "列出应用", "apps", input.safePreview()) {
                textJson {
                    put("apps", queryLaunchableApps(input.string("query").orEmpty(), input.limit(default = 80, max = 200)))
                }
            }
        }
    )

    private val appOpenTool = Tool(
        name = "app_open",
        description = "Open an installed launchable app by package name.",
        parameters = {
            obj(
                "package_name" to stringProp("Android package name to launch."),
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

    private val appsInstalledListTool = Tool(
        name = "apps_installed_list",
        description = "List installed packages visible to AmberAgent. Debug/advanced experiment path; QUERY_ALL_PACKAGES may be restricted by Google Play policy.",
        parameters = {
            obj(
                "query" to stringProp("Optional app label or package filter."),
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

    private val appInfoTool = Tool(
        name = "app_info",
        description = "Return label, version, launchability, install source, and optional declared permissions for a package.",
        parameters = {
            obj(
                "package_name" to stringProp("Android package name."),
                "include_permissions" to booleanProp("Include declared permissions. Defaults to false."),
                required = listOf("package_name")
            )
        },
        execute = { input ->
            deps.trackSystemTool("app_info", "读取应用信息", "apps", input) {
                textJson {
                    put("app", packageInfoJson(input.requiredString("package_name"), input.boolean("include_permissions") ?: false))
                }
            }
        }
    )

    private val batteryStatusTool = Tool(
        name = "battery_status",
        description = "Read device battery level, charging state, and power-save mode.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("battery_status", "读取电池状态", "apps", input) {
                textJson { put("battery", batteryStatusJson()) }
            }
        }
    )

    private val networkStatusTool = Tool(
        name = "network_status",
        description = "Read coarse connectivity status, transport type, and VPN/roaming hints.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("network_status", "读取网络状态", "apps", input) {
                textJson { put("network", networkStatusJson()) }
            }
        }
    )

    private val wifiStatusTool = Tool(
        name = "wifi_status",
        description = "Read Wi-Fi enabled state and redacted current SSID/IP if available. Does not scan nearby Wi-Fi.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("wifi_status", "读取 Wi-Fi 状态", "apps", input) {
                textJson { put("wifi", wifiStatusJson()) }
            }
        }
    )

    private val deviceInfoTool = Tool(
        name = "device_info",
        description = "Read device brand, model, Android version, ABI, and screen metrics.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("device_info", "读取设备信息", "apps", input) {
                textJson { put("device", deviceInfoJson()) }
            }
        }
    )

    private val settingsOpenTool = Tool(
        name = "settings_open",
        description = "Open a whitelisted Android settings page, such as accessibility, notification access, app details, overlay, battery optimization, location, or default apps.",
        parameters = {
            obj(
                "target" to enumProp(
                    "Settings page target.",
                    listOf(
                        "app_details",
                        "accessibility",
                        "notification_access",
                        "usage_access",
                        "overlay",
                        "battery_optimization",
                        "location",
                        "default_apps",
                    )
                ),
                required = listOf("target")
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.trackSystemTool("settings_open", "打开系统设置", "apps", input) {
                val intent = settingsIntent(input.requiredString("target"))
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                textJson {
                    put("success", true)
                    put("target", input.requiredString("target"))
                }
            }
        }
    )

    private val intentOpenTool = Tool(
        name = "intent_open",
        description = "Open a whitelisted Android intent action with optional data URI. Dangerous or non-whitelisted actions are rejected.",
        parameters = {
            obj(
                "action" to enumProp("Intent action.", listOf("view", "dial", "sendto", "web_search")),
                "data_uri" to stringProp("Optional data URI. Only http/https/tel/mailto/smsto are allowed."),
                required = listOf("action")
            )
        },
        needsApproval = true,
        execute = { input ->
            deps.trackSystemTool("intent_open", "打开 Intent", "apps", input.safePreview()) {
                val intent = whitelistedIntent(input.requiredString("action"), input.string("data_uri"))
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                textJson {
                    put("success", true)
                    put("action", input.requiredString("action"))
                }
            }
        }
    )

    private val shareTextTool = Tool(
        name = "share_text",
        description = "Open Android share sheet with plain text. Requires approval because it sends data outside AmberAgent.",
        parameters = {
            obj(
                "text" to stringProp("Text to share."),
                "title" to stringProp("Optional chooser title."),
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("share_text", "分享文本", "apps", input.safePreview()) {
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, input.requiredString("text"))
                val chooser = Intent.createChooser(intent, input.string("title") ?: "Share from AmberAgent")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                textJson { put("success", true) }
            }
        }
    )

    private val shareFileTool = Tool(
        name = "share_file",
        description = "Share a file from /workspace through the Android share sheet (e.g. send to WeChat, save to Drive). " +
            "Use only when the user explicitly asks to share/send/export/forward the file to another app or person. " +
            "Do NOT use this when the user just wants to preview/open/browse the artifact inside AmberAgent — for that, " +
            "re-emit the artifact as a show-widget block in your reply (see widget guidance).",
        parameters = {
            obj(
                "path" to stringProp("Workspace-relative file path."),
                "title" to stringProp("Optional chooser title."),
                required = listOf("path")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("share_file", "分享文件", "apps", input.safePreview()) {
                val path = input.requiredString("path")
                val file = cacheWorkspaceFileForSharing(path)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND)
                    .setType(file.extension.takeIf { it.isNotBlank() }?.let { mimeFromExtension(it) } ?: "application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(intent, input.string("title") ?: "Share from AmberAgent")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                textJson {
                    put("success", true)
                    put("path", path)
                    put("size_bytes", file.length())
                }
            }
        }
    )

    private val notificationPostTool = Tool(
        name = "notification_post",
        description = "Post an AmberAgent notification for a task reminder or status summary.",
        parameters = {
            obj(
                "title" to stringProp("Notification title."),
                "text" to stringProp("Notification text."),
                required = listOf("title", "text")
            )
        },
        execute = { input ->
            deps.trackSystemTool("notification_post", "发布通知", "apps", input.safePreview()) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                val notification = NotificationCompat.Builder(context, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.small_icon)
                    .setContentTitle(input.requiredString("title").take(80))
                    .setContentText(input.requiredString("text").take(160))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(input.requiredString("text").take(800)))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                notificationManager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
                textJson { put("success", true) }
            }
        }
    )

    private fun queryLaunchableApps(query: String, limit: Int) = buildJsonArray {
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

    private fun queryInstalledApps(query: String, includeSystem: Boolean, includePermissions: Boolean, limit: Int) = buildJsonArray {
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
                add(packageInfoJson(info.packageName, includePermissions))
            }
    }

    private fun packageInfoJson(packageName: String, includePermissions: Boolean) = buildJsonObject {
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

    private fun batteryStatusJson() = buildJsonObject {
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        put("level_percent", level)
        put("power_save_mode", pm?.isPowerSaveMode == true)
        put("charging", runCatching {
            val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false))
    }

    private fun networkStatusJson() = buildJsonObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(network)
        put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
        put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        put("transport", buildJsonArray {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        })
    }

    private fun wifiStatusJson() = buildJsonObject {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        put("enabled", wifi?.isWifiEnabled == true)
        @Suppress("DEPRECATION")
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removeSurrounding("\"").orEmpty()
        put("ssid_redacted", if (ssid.isBlank() || ssid == WifiManager.UNKNOWN_SSID) "" else redactMiddle(ssid))
        @Suppress("DEPRECATION")
        put("ip_address_int", info?.ipAddress ?: 0)
    }

    private fun deviceInfoJson() = buildJsonObject {
        val metrics = context.resources.displayMetrics
        val uiMode = context.getSystemService(UiModeManager::class.java)
        put("brand", Build.BRAND.orEmpty())
        put("manufacturer", Build.MANUFACTURER.orEmpty())
        put("model", Build.MODEL.orEmpty())
        put("device", Build.DEVICE.orEmpty())
        put("android_sdk", Build.VERSION.SDK_INT)
        put("android_release", Build.VERSION.RELEASE.orEmpty())
        put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(it) } })
        put("screen_width_px", metrics.widthPixels)
        put("screen_height_px", metrics.heightPixels)
        put("density", metrics.density.toDouble())
        put("ui_mode_type", uiMode?.currentModeType ?: 0)
    }

    private fun settingsIntent(target: String): Intent = when (target) {
        "app_details" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "usage_access" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        "battery_optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "default_apps" -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        else -> error("Unsupported settings target: $target")
    }

    private fun whitelistedIntent(action: String, dataUri: String?): Intent {
        val intentAction = when (action) {
            "view" -> Intent.ACTION_VIEW
            "dial" -> Intent.ACTION_DIAL
            "sendto" -> Intent.ACTION_SENDTO
            "web_search" -> Intent.ACTION_WEB_SEARCH
            else -> error("Unsupported intent action: $action")
        }
        val uri = dataUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri != null) {
            require(uri.scheme in setOf("http", "https", "tel", "mailto", "smsto")) {
                "Unsupported data_uri scheme: ${uri.scheme}"
            }
        }
        return Intent(intentAction, uri)
    }

    private suspend fun cacheWorkspaceFileForSharing(path: String): File {
        val bytes = workspaceManager.readBytes(path)
        val safeName = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared.bin" }
        val dir = File(context.cacheDir, "agent-share").apply { mkdirs() }
        return File(dir, safeName).apply { writeBytes(bytes) }
    }

    private fun mimeFromExtension(extension: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"

    private fun redactMiddle(value: String): String {
        if (value.length <= 3) return "***"
        return value.take(2) + "***" + value.takeLast(1)
    }

}
