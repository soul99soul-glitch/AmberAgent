package me.rerere.rikkahub.data.agent.tools

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.system.AmberNotificationListenerService
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.io.File
import kotlin.coroutines.resume

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

    private val mediaSearchTool = Tool(
        name = "media_search",
        description = "Search Android MediaStore images, videos, or audio after the matching media permission is granted.",
        parameters = {
            obj(
                "type" to enumProp("Media type.", listOf("images", "video", "audio", "all")),
                "query" to stringProp("Optional file name filter."),
                "limit" to integerProp("Maximum media entries. Defaults to 30."),
            )
        },
        execute = { input ->
            val type = input.string("type") ?: "all"
            val capabilities = when (type) {
                "images" -> listOf("media_images")
                "video" -> listOf("media_video")
                "audio" -> listOf("media_audio")
                else -> listOf("media_images", "media_video", "media_audio")
            }
            capabilities.drop(1).forEach { capability ->
                permissionBroker.ensureGranted(
                    capabilityId = capability,
                    toolName = "media_search",
                    reason = "搜索媒体库",
                )
            }
            deps.trackSystemTool("media_search", "搜索媒体库", capabilities.first(), input.safePreview()) {
                textJson {
                    put("media", queryMedia(type = type, query = input.string("query").orEmpty(), limit = input.limit(default = 30, max = 100)))
                }
            }
        }
    )

    private val locationCurrentTool = Tool(
        name = "location_current",
        description = "Return the latest available device location from LocationManager after location permission is granted.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("location_current", "读取当前位置", "location_current", input) {
                val location = currentOrLatestLocation()
                textJson {
                    if (location == null) {
                        put("available", false)
                        put("reason", "No recent location is available. Enable location providers or open a maps app once, then retry.")
                    } else {
                        put("available", true)
                        put("provider", location.provider.orEmpty())
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy_meters", location.accuracy.toDouble())
                        put("time_epoch_ms", location.time)
                    }
                }
            }
        }
    )

    private val audioRecordOnceTool = Tool(
        name = "audio_record_once",
        description = "Record a short microphone clip to app-private storage. Requires RECORD_AUDIO and explicit approval.",
        parameters = {
            obj(
                "duration_ms" to integerProp("Recording duration in milliseconds. Defaults to 5000, max 30000."),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("audio_record_once", "录制音频", "audio_record", input.safePreview()) {
                val file = recordAudioOnce(input.limit("duration_ms", default = 5_000, max = 30_000).toLong())
                textJson {
                    put("artifact_type", "audio")
                    put("path", file.absolutePath)
                    put("size_bytes", file.length())
                }
            }
        }
    )

    private val notificationListTool = Tool(
        name = "notification_list",
        description = "List active notification summaries after Notification Access is enabled.",
        parameters = {
            obj(
                "limit" to integerProp("Maximum notifications. Defaults to 30."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("notification_list", "读取通知", "notification_access", input) {
                textJson {
                    put("notifications", queryNotifications(input.limit(default = 30, max = 80)))
                }
            }
        }
    )

    private val usageStatsListTool = Tool(
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
                    put("usage_stats", queryUsageStats(input.long("since_epoch_ms"), input.limit(default = 30, max = 100)))
                }
            }
        }
    )

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

    private fun queryMedia(type: String, query: String, limit: Int) = buildJsonArray {
        val targets = when (type) {
            "images" -> listOf("image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            "video" -> listOf("video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            "audio" -> listOf("audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            else -> listOf(
                "image" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "audio" to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            )
        }
        var count = 0
        for ((kind, uri) in targets) {
            if (count >= limit) break
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                ),
                query.takeIf { it.isNotBlank() }?.let { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" },
                query.takeIf { it.isNotBlank() }?.let { arrayOf("%$it%") },
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idIndex)
                    add(buildJsonObject {
                        put("type", kind)
                        put("uri", ContentUris.withAppendedId(uri, id).toString())
                        put("name", cursor.getString(nameIndex).orEmpty())
                        put("mime_type", cursor.getString(mimeIndex).orEmpty())
                        put("size_bytes", cursor.getLong(sizeIndex))
                        put("date_modified_seconds", cursor.getLong(modifiedIndex))
                    })
                    count++
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentOrLatestLocation(): Location? {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        latestLocation(locationManager)
            ?.takeIf { System.currentTimeMillis() - it.time < 5L * 60L * 1000L }
            ?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in locationManager.getProviders(true)) {
                val current = withTimeoutOrNull(5_000L) {
                    suspendCancellableCoroutine { continuation ->
                        val cancellationSignal = CancellationSignal()
                        continuation.invokeOnCancellation { cancellationSignal.cancel() }
                        locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            context.mainExecutor
                        ) { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                    }
                }
                if (current != null) return current
            }
        }

        return latestLocation(locationManager)
    }

    @SuppressLint("MissingPermission")
    private fun latestLocation(locationManager: LocationManager): Location? {
        return locationManager.getProviders(true)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
    }

    private suspend fun recordAudioOnce(durationMillis: Long): File {
        val outputDir = File(context.filesDir, "agent-artifacts/audio").apply { mkdirs() }
        val output = File(outputDir, "recording-${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            delay(durationMillis)
            recorder.stop()
        } finally {
            recorder.release()
        }
        return output
    }

    private fun queryNotifications(limit: Int) = buildJsonArray {
        AmberNotificationListenerService.getActiveNotificationsSnapshot()
            .take(limit)
            .forEach { sbn ->
                val extras = sbn.notification.extras
                add(buildJsonObject {
                    put("package_name", sbn.packageName)
                    put("posted_at_epoch_ms", sbn.postTime)
                    put("title", extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty())
                    put("text", extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty().take(240))
                })
            }
    }

    private fun queryUsageStats(since: Long?, limit: Int) = buildJsonArray {
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
                    put("label", appLabel(usage.packageName))
                    put("last_time_used_epoch_ms", usage.lastTimeUsed)
                    put("total_time_foreground_ms", usage.totalTimeInForeground)
                })
            }
    }

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

    private fun appLabel(packageName: String): String {
        val pm = context.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        }.getOrDefault("")
    }

    private fun redactMiddle(value: String): String {
        if (value.length <= 3) return "***"
        return value.take(2) + "***" + value.takeLast(1)
    }

}
