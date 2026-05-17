package me.rerere.rikkahub.data.agent.tools

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
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
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
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
import java.time.Instant
import java.time.ZoneId
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

    private val contactsSearchTool = Tool(
        name = "contacts_search",
        description = "Search Android contacts by name, phone, or email after READ_CONTACTS is granted. Phone numbers are masked by default.",
        parameters = {
            obj(
                "query" to stringProp("Name, phone, or email keyword. Empty lists recent contacts."),
                "limit" to integerProp("Maximum contacts to return. Defaults to 20."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("contacts_search", "搜索通讯录", "contacts_read", input) {
                textJson {
                    put("contacts", queryContacts(input.string("query").orEmpty(), input.limit(default = 20, max = 50)))
                }
            }
        }
    )

    private val contactsWriteTool = Tool(
        name = "contacts_write",
        description = "Create a contact in Android Contacts. Requires WRITE_CONTACTS and explicit approval.",
        parameters = {
            obj(
                "name" to stringProp("Contact display name."),
                "phone" to stringProp("Optional phone number."),
                "email" to stringProp("Optional email address."),
                required = listOf("name")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("contacts_write", "写入联系人", "contacts_write", input.safePreview()) {
                val name = input.requiredString("name")
                val phone = input.string("phone").orEmpty()
                val email = input.string("email").orEmpty()
                if (phone.isBlank() && email.isBlank()) error("phone or email is required")
                val id = createContact(name = name, phone = phone, email = email)
                textJson {
                    put("success", true)
                    put("raw_contact_id", id)
                }
            }
        }
    )

    private val smsListTool = Tool(
        name = "sms_list",
        description = "List SMS messages from inbox, sent, or all after READ_SMS is granted. Bodies are returned as short previews by default.",
        parameters = {
            obj(
                "box" to enumProp("SMS box to read.", listOf("all", "inbox", "sent")),
                "sender" to stringProp("Optional sender address filter."),
                "since_epoch_ms" to integerProp("Only include SMS after this Unix epoch millis."),
                "limit" to integerProp("Maximum messages. Defaults to 20."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("sms_list", "读取短信列表", "sms_read", input.safePreview()) {
                textJson {
                    put("messages", querySms(input, previewOnly = true))
                }
            }
        }
    )

    private val smsReadTool = Tool(
        name = "sms_read",
        description = "Read SMS content by message_id or thread_id after READ_SMS is granted.",
        parameters = {
            obj(
                "message_id" to stringProp("SMS message _id."),
                "thread_id" to stringProp("SMS thread_id."),
                "limit" to integerProp("Maximum messages when reading a thread. Defaults to 20."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("sms_read", "读取短信内容", "sms_read", input.safePreview()) {
                textJson {
                    put("messages", querySms(input, previewOnly = false))
                }
            }
        }
    )

    private val smsSendTool = Tool(
        name = "sms_send",
        description = "Send an SMS from this device. Requires SEND_SMS and explicit approval.",
        parameters = {
            obj(
                "phone_number" to stringProp("Recipient phone number."),
                "message" to stringProp("SMS body."),
                required = listOf("phone_number", "message")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("sms_send", "发送短信", "sms_send", input.safePreview()) {
                val phoneNumber = input.requiredString("phone_number")
                val message = input.requiredString("message")
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                } ?: error("SmsManager is unavailable")
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                textJson {
                    put("success", true)
                    put("phone_number", maskPhone(phoneNumber))
                    put("message_chars", message.length)
                }
            }
        }
    )

    private val devicePhoneStateTool = Tool(
        name = "device_phone_state",
        description = "Read coarse phone/SIM state after READ_PHONE_STATE or READ_PHONE_NUMBERS is granted.",
        parameters = { obj() },
        execute = { input ->
            deps.trackSystemTool("device_phone_state", "读取电话状态", "phone_state", input) {
                val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                textJson {
                    put("phone_type", telephonyManager?.phoneType ?: TelephonyManager.PHONE_TYPE_NONE)
                    put("network_operator", telephonyManager?.networkOperatorName.orEmpty())
                    put("sim_operator", telephonyManager?.simOperatorName.orEmpty())
                    @Suppress("DEPRECATION", "MissingPermission")
                    put("line1_number", telephonyManager?.line1Number?.let(::maskPhone).orEmpty())
                }
            }
        }
    )

    private val callLogListTool = Tool(
        name = "call_log_list",
        description = "List recent Android call log entries after READ_CALL_LOG is granted.",
        parameters = {
            obj(
                "limit" to integerProp("Maximum call log entries. Defaults to 20."),
                "since_epoch_ms" to integerProp("Only include entries after this Unix epoch millis."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("call_log_list", "读取通话记录", "call_log_read", input.safePreview()) {
                textJson {
                    put("calls", queryCallLogs(input.limit(default = 20, max = 50), input.long("since_epoch_ms")))
                }
            }
        }
    )

    private val callPhoneTool = Tool(
        name = "call_phone",
        description = "Open the dialer by default. If direct_call=true, directly starts a phone call and requires CALL_PHONE plus explicit approval.",
        parameters = {
            obj(
                "phone_number" to stringProp("Phone number to dial or call."),
                "direct_call" to booleanProp("Directly place the call. Defaults to false and opens the dialer instead."),
                required = listOf("phone_number")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            val direct = input.boolean("direct_call") ?: false
            val capability = if (direct) "call_phone" else "apps"
            deps.trackSystemTool("call_phone", "拨打电话", capability, input.safePreview()) {
                val phoneNumber = input.requiredString("phone_number")
                val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
                context.startActivity(Intent(action, Uri.parse("tel:$phoneNumber")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                textJson {
                    put("success", true)
                    put("direct_call", direct)
                    put("phone_number", maskPhone(phoneNumber))
                }
            }
        }
    )

    private val calendarListTool = Tool(
        name = "calendar_list",
        description = "List Android calendar events after READ_CALENDAR is granted.",
        parameters = {
            obj(
                "from_epoch_ms" to integerProp("Start Unix epoch millis. Defaults to now."),
                "to_epoch_ms" to integerProp("End Unix epoch millis. Defaults to 7 days from start."),
                "limit" to integerProp("Maximum events. Defaults to 30."),
            )
        },
        execute = { input ->
            deps.trackSystemTool("calendar_list", "读取日历事件", "calendar_read", input.safePreview()) {
                textJson {
                    put("events", queryCalendarEvents(input))
                }
            }
        }
    )

    private val calendarCreateTool = Tool(
        name = "calendar_create",
        description = "Create an Android calendar event. Requires WRITE_CALENDAR and explicit approval.",
        parameters = {
            obj(
                "title" to stringProp("Event title."),
                "start_time" to stringProp("ISO-8601 start time, for example 2026-05-03T10:00:00+08:00."),
                "end_time" to stringProp("ISO-8601 end time."),
                "start_epoch_ms" to integerProp("Start Unix epoch millis. Used if start_time is absent."),
                "end_epoch_ms" to integerProp("End Unix epoch millis. Used if end_time is absent."),
                "description" to stringProp("Optional event description."),
                "location" to stringProp("Optional event location."),
                required = listOf("title")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            deps.trackSystemTool("calendar_create", "创建日历事件", "calendar_write", input.safePreview()) {
                val eventId = createCalendarEvent(input)
                textJson {
                    put("success", true)
                    put("event_id", eventId)
                }
            }
        }
    )

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

    private fun queryContacts(query: String, limit: Int) = buildJsonArray {
        val seen = mutableSetOf<Long>()
        val phoneSelection = if (query.isBlank()) null else {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        }
        val phoneArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        var count = 0
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            phoneSelection,
            phoneArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && count < limit) {
                val contactId = cursor.getLong(idIndex)
                if (!seen.add(contactId)) continue
                val name = cursor.getString(nameIndex).orEmpty()
                val phone = cursor.getString(phoneIndex).orEmpty()
                add(buildJsonObject {
                    put("contact_id", contactId)
                    put("name", name)
                    put("phone_masked", maskPhone(phone))
                })
                count++
            }
        }

        if (count < limit) {
            val emailSelection = if (query.isBlank()) null else {
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?"
            }
            val emailArgs = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                emailSelection,
                emailArgs,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY)
                val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext() && count < limit) {
                    val contactId = cursor.getLong(idIndex)
                    if (!seen.add(contactId)) continue
                    val name = cursor.getString(nameIndex).orEmpty()
                    val email = cursor.getString(emailIndex).orEmpty()
                    add(buildJsonObject {
                        put("contact_id", contactId)
                        put("name", name)
                        put("email_masked", maskEmail(email))
                    })
                    count++
                }
            }
        }
    }

    private fun createContact(name: String, phone: String, email: String): Long {
        val ops = arrayListOf<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        if (phone.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        if (email.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.DATA, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                .build()
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val rawContactUri = results.firstOrNull()?.uri ?: error("Contact insert did not return a raw contact URI")
        return ContentUris.parseId(rawContactUri)
    }

    private fun querySms(input: JsonElement, previewOnly: Boolean) = buildJsonArray {
        val messageId = input.string("message_id")
        val threadId = input.string("thread_id")
        val sender = input.string("sender")
        val since = input.long("since_epoch_ms")
        val limit = input.limit(default = 20, max = 100)
        val box = input.string("box") ?: "all"
        val uri = when (box) {
            "inbox" -> Telephony.Sms.Inbox.CONTENT_URI
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            else -> Telephony.Sms.CONTENT_URI
        }
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        messageId?.let {
            clauses += "${Telephony.Sms._ID} = ?"
            args += it
        }
        threadId?.let {
            clauses += "${Telephony.Sms.THREAD_ID} = ?"
            args += it
        }
        sender?.takeIf { it.isNotBlank() }?.let {
            clauses += "${Telephony.Sms.ADDRESS} LIKE ?"
            args += "%$it%"
        }
        since?.let {
            clauses += "${Telephony.Sms.DATE} >= ?"
            args += it.toString()
        }
        var count = 0
        context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.BODY,
            ),
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (cursor.moveToNext() && count < limit) {
                val body = cursor.getString(bodyIndex).orEmpty()
                add(buildJsonObject {
                    put("message_id", cursor.getLong(idIndex))
                    put("thread_id", cursor.getLong(threadIndex))
                    put("address_masked", maskPhone(cursor.getString(addressIndex).orEmpty()))
                    put("date_epoch_ms", cursor.getLong(dateIndex))
                    put("type", cursor.getInt(typeIndex))
                    if (previewOnly) {
                        put("preview", body.take(160))
                        put("body_chars", body.length)
                    } else {
                        put("body", body)
                    }
                })
                count++
            }
        }
    }

    private fun queryCallLogs(limit: Int, since: Long?) = buildJsonArray {
        val selection = since?.let { "${CallLog.Calls.DATE} >= ?" }
        val args = since?.let { arrayOf(it.toString()) }
        var count = 0
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            selection,
            args,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && count < limit) {
                add(buildJsonObject {
                    put("call_id", cursor.getLong(idIndex))
                    put("number_masked", maskPhone(cursor.getString(numberIndex).orEmpty()))
                    put("name", cursor.getString(nameIndex).orEmpty())
                    put("type", cursor.getInt(typeIndex))
                    put("date_epoch_ms", cursor.getLong(dateIndex))
                    put("duration_seconds", cursor.getLong(durationIndex))
                })
                count++
            }
        }
    }

    private fun queryCalendarEvents(input: JsonElement) = buildJsonArray {
        val now = System.currentTimeMillis()
        val from = input.long("from_epoch_ms") ?: now
        val to = input.long("to_epoch_ms") ?: (from + 7L * 24L * 60L * 60L * 1000L)
        val limit = input.limit(default = 30, max = 100)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        var count = 0
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext() && count < limit) {
                add(buildJsonObject {
                    put("event_id", cursor.getLong(idIndex))
                    put("title", cursor.getString(titleIndex).orEmpty())
                    put("begin_epoch_ms", cursor.getLong(beginIndex))
                    put("end_epoch_ms", cursor.getLong(endIndex))
                    put("location", cursor.getString(locationIndex).orEmpty())
                    put("calendar", cursor.getString(calendarIndex).orEmpty())
                })
                count++
            }
        }
    }

    private fun createCalendarEvent(input: JsonElement): Long {
        val calendarId = firstWritableCalendarId()
        val start = input.timeMillis("start_time", "start_epoch_ms")
        val end = input.timeMillis("end_time", "end_epoch_ms")
        require(end > start) { "end_time must be after start_time" }
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, input.requiredString("title"))
            put(CalendarContract.Events.DESCRIPTION, input.string("description").orEmpty())
            put(CalendarContract.Events.EVENT_LOCATION, input.string("location").orEmpty())
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Failed to create calendar event")
        return ContentUris.parseId(uri)
    }

    private fun firstWritableCalendarId(): Long {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        error("No writable calendar found")
    }

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

    private fun JsonElement.timeMillis(isoName: String, epochName: String): Long {
        string(isoName)?.takeIf { it.isNotBlank() }?.let { return Instant.parse(it).toEpochMilli() }
        return long(epochName) ?: error("$isoName or $epochName is required")
    }
}
