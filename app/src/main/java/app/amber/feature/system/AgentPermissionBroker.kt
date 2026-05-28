package app.amber.feature.system

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig

private const val TAG = "AgentPermissionBroker"

enum class AgentPermissionRisk {
    Normal,
    Sensitive,
    High,
}

enum class AgentPermissionStatus {
    Granted,
    Denied,
    SpecialNeeded,
    Unsupported,
}

enum class AgentSpecialAccess {
    NotificationListener,
    UsageStats,
    Overlay,
    IgnoreBatteryOptimizations,
    ExactAlarm,
    ManageAllFiles,
}

enum class RuntimeGrantMode {
    All,
    Any,
}

data class RuntimePermissionSpec(
    val permission: String,
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
) {
    fun appliesToCurrentSdk(): Boolean =
        Build.VERSION.SDK_INT in minSdk..maxSdk
}

data class AgentPermissionCapability(
    val id: String,
    val title: String,
    val description: String,
    val runtimePermissions: List<RuntimePermissionSpec> = emptyList(),
    val runtimeGrantMode: RuntimeGrantMode = RuntimeGrantMode.All,
    val specialAccess: AgentSpecialAccess? = null,
    val risk: AgentPermissionRisk = AgentPermissionRisk.Normal,
    val toolNames: List<String> = emptyList(),
    val minSdk: Int = 1,
    val debugOnly: Boolean = false,
) {
    fun currentRuntimePermissions(): List<String> =
        runtimePermissions.filter { it.appliesToCurrentSdk() }.map { it.permission }.distinct()
}

class AgentPermissionBroker(
    private val context: Context,
) {
    val capabilities: List<AgentPermissionCapability> = AgentPermissionRegistry.capabilities

    fun getCapability(id: String): AgentPermissionCapability =
        capabilities.firstOrNull { it.id == id } ?: error("Unknown permission capability: $id")

    fun getStatus(capabilityId: String): AgentPermissionStatus =
        getStatus(getCapability(capabilityId))

    fun getStatus(capability: AgentPermissionCapability): AgentPermissionStatus {
        if (capability.debugOnly && !BuildConfig.DEBUG) return AgentPermissionStatus.Unsupported
        if (Build.VERSION.SDK_INT < capability.minSdk) return AgentPermissionStatus.Unsupported

        capability.specialAccess?.let { special ->
            return if (isSpecialAccessGranted(special)) {
                AgentPermissionStatus.Granted
            } else {
                AgentPermissionStatus.SpecialNeeded
            }
        }

        val permissions = capability.currentRuntimePermissions()
        if (permissions.isEmpty()) return AgentPermissionStatus.Granted

        val grantedCount = permissions.count { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        return when (capability.runtimeGrantMode) {
            RuntimeGrantMode.All -> if (grantedCount == permissions.size) {
                AgentPermissionStatus.Granted
            } else {
                AgentPermissionStatus.Denied
            }

            RuntimeGrantMode.Any -> if (grantedCount > 0) {
                AgentPermissionStatus.Granted
            } else {
                AgentPermissionStatus.Denied
            }
        }
    }

    fun ensureGranted(capabilityId: String, toolName: String, reason: String) {
        val capability = getCapability(capabilityId)
        val status = getStatus(capability)
        if (status != AgentPermissionStatus.Granted) {
            error(
                buildString {
                    append("System permission required: ${capability.title}. ")
                    append("Status: $status. ")
                    append("Open Settings > Agent Runtime > System Access to grant it.")
                }
            )
        }
        auditPermissionUse(toolName = toolName, capabilityId = capabilityId, reason = reason)
    }

    fun runtimePermissionsFor(capability: AgentPermissionCapability): List<String> =
        capability.currentRuntimePermissions()

    fun runtimePermissionsForCoreBatch(): List<String> =
        capabilities
            .filter { !it.debugOnly && it.specialAccess == null && it.risk != AgentPermissionRisk.High }
            .flatMap { it.currentRuntimePermissions() }
            .distinct()

    fun createSpecialAccessIntent(capabilityId: String): Intent? =
        getCapability(capabilityId).specialAccess?.let(::createSpecialAccessIntent)

    fun auditPermissionUse(toolName: String, capabilityId: String, reason: String) {
        Logging.log(TAG, "tool=$toolName capability=$capabilityId reason=$reason")
    }

    private fun isSpecialAccessGranted(access: AgentSpecialAccess): Boolean =
        when (access) {
            AgentSpecialAccess.NotificationListener -> notificationListenerEnabled()
            AgentSpecialAccess.UsageStats -> usageStatsEnabled()
            AgentSpecialAccess.Overlay -> Settings.canDrawOverlays(context)
            AgentSpecialAccess.IgnoreBatteryOptimizations -> {
                val powerManager = context.getSystemService(PowerManager::class.java)
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            }

            AgentSpecialAccess.ExactAlarm -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    true
                } else {
                    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
                }
            }

            AgentSpecialAccess.ManageAllFiles -> {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
            }
        }

    private fun createSpecialAccessIntent(access: AgentSpecialAccess): Intent =
        when (access) {
            AgentSpecialAccess.NotificationListener -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            AgentSpecialAccess.UsageStats -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AgentSpecialAccess.Overlay -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

            AgentSpecialAccess.IgnoreBatteryOptimizations -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )

            AgentSpecialAccess.ExactAlarm -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }

            AgentSpecialAccess.ManageAllFiles -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
        }

    private fun notificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { component ->
            component.contains(context.packageName, ignoreCase = true)
        }
    }

    private fun usageStatsEnabled(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

object AgentPermissionRegistry {
    val capabilities = listOf(
        AgentPermissionCapability(
            id = "contacts_read",
            title = "通讯录读取",
            description = "搜索联系人姓名、电话和邮箱。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.READ_CONTACTS)),
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("contacts_search"),
        ),
        AgentPermissionCapability(
            id = "contacts_write",
            title = "通讯录写入",
            description = "创建或更新联系人。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.WRITE_CONTACTS)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("contacts_write"),
        ),
        AgentPermissionCapability(
            id = "sms_read",
            title = "短信读取",
            description = "读取本机短信列表和短信内容。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.READ_SMS),
                RuntimePermissionSpec(Manifest.permission.RECEIVE_SMS),
                RuntimePermissionSpec(Manifest.permission.RECEIVE_MMS),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.High,
            toolNames = listOf("sms_list", "sms_read"),
        ),
        AgentPermissionCapability(
            id = "sms_send",
            title = "短信发送",
            description = "直接从本机号码发送短信。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.SEND_SMS)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("sms_send"),
        ),
        AgentPermissionCapability(
            id = "phone_state",
            title = "电话状态",
            description = "读取 SIM 和电话状态。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.READ_PHONE_STATE),
                RuntimePermissionSpec(Manifest.permission.READ_PHONE_NUMBERS, minSdk = Build.VERSION_CODES.O),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("device_phone_state"),
        ),
        AgentPermissionCapability(
            id = "call_log_read",
            title = "通话记录读取",
            description = "读取最近通话记录。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.READ_CALL_LOG)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("call_log_list"),
        ),
        AgentPermissionCapability(
            id = "call_phone",
            title = "直接拨号",
            description = "无需再经过拨号盘确认，直接发起电话呼叫。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.CALL_PHONE)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("call_phone"),
        ),
        AgentPermissionCapability(
            id = "calendar_read",
            title = "日历读取",
            description = "读取系统日历事件。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.READ_CALENDAR)),
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("calendar_list"),
        ),
        AgentPermissionCapability(
            id = "calendar_write",
            title = "日历写入",
            description = "创建系统日历事件。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.WRITE_CALENDAR)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("calendar_create"),
        ),
        AgentPermissionCapability(
            id = "media_images",
            title = "图片媒体",
            description = "搜索系统图片媒体库。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.READ_EXTERNAL_STORAGE, maxSdk = Build.VERSION_CODES.S_V2),
                RuntimePermissionSpec(Manifest.permission.READ_MEDIA_IMAGES, minSdk = Build.VERSION_CODES.TIRAMISU),
                RuntimePermissionSpec(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("media_search"),
        ),
        AgentPermissionCapability(
            id = "media_video",
            title = "视频媒体",
            description = "搜索系统视频媒体库。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.READ_EXTERNAL_STORAGE, maxSdk = Build.VERSION_CODES.S_V2),
                RuntimePermissionSpec(Manifest.permission.READ_MEDIA_VIDEO, minSdk = Build.VERSION_CODES.TIRAMISU),
                RuntimePermissionSpec(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, minSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("media_search"),
        ),
        AgentPermissionCapability(
            id = "media_audio",
            title = "音频媒体",
            description = "搜索系统音频媒体库。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.READ_EXTERNAL_STORAGE, maxSdk = Build.VERSION_CODES.S_V2),
                RuntimePermissionSpec(Manifest.permission.READ_MEDIA_AUDIO, minSdk = Build.VERSION_CODES.TIRAMISU),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("media_search"),
        ),
        AgentPermissionCapability(
            id = "location_current",
            title = "当前位置",
            description = "读取最近一次系统定位结果。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.ACCESS_COARSE_LOCATION),
                RuntimePermissionSpec(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("location_current"),
        ),
        AgentPermissionCapability(
            id = "audio_record",
            title = "麦克风录音",
            description = "录制一段短音频作为工具产物。",
            runtimePermissions = listOf(RuntimePermissionSpec(Manifest.permission.RECORD_AUDIO)),
            risk = AgentPermissionRisk.High,
            toolNames = listOf("audio_record_once"),
        ),
        AgentPermissionCapability(
            id = "nearby_devices",
            title = "附近设备",
            description = "扫描或连接蓝牙、附近 Wi-Fi 设备。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.BLUETOOTH_SCAN, minSdk = Build.VERSION_CODES.S),
                RuntimePermissionSpec(Manifest.permission.BLUETOOTH_CONNECT, minSdk = Build.VERSION_CODES.S),
                RuntimePermissionSpec(Manifest.permission.BLUETOOTH_ADVERTISE, minSdk = Build.VERSION_CODES.S),
                RuntimePermissionSpec(Manifest.permission.NEARBY_WIFI_DEVICES, minSdk = Build.VERSION_CODES.TIRAMISU),
            ),
            runtimeGrantMode = RuntimeGrantMode.Any,
            risk = AgentPermissionRisk.Sensitive,
        ),
        AgentPermissionCapability(
            id = "activity_recognition",
            title = "活动识别",
            description = "读取步行、跑步、乘车等活动识别结果。",
            runtimePermissions = listOf(
                RuntimePermissionSpec(Manifest.permission.ACTIVITY_RECOGNITION, minSdk = Build.VERSION_CODES.Q),
            ),
            risk = AgentPermissionRisk.Sensitive,
        ),
        AgentPermissionCapability(
            id = "notification_access",
            title = "通知读取",
            description = "读取当前活跃通知摘要。",
            specialAccess = AgentSpecialAccess.NotificationListener,
            risk = AgentPermissionRisk.High,
            toolNames = listOf("notification_list"),
        ),
        AgentPermissionCapability(
            id = "usage_access",
            title = "使用情况访问",
            description = "读取最近使用过的应用和使用时长。",
            specialAccess = AgentSpecialAccess.UsageStats,
            risk = AgentPermissionRisk.Sensitive,
            toolNames = listOf("usage_stats_list"),
        ),
        AgentPermissionCapability(
            id = "overlay",
            title = "悬浮窗",
            description = "允许显示覆盖在其他应用上方的 Agent 控件。",
            specialAccess = AgentSpecialAccess.Overlay,
            risk = AgentPermissionRisk.Sensitive,
        ),
        AgentPermissionCapability(
            id = "battery_optimization",
            title = "忽略电池优化",
            description = "降低长时间 Agent 任务被系统暂停的概率。",
            specialAccess = AgentSpecialAccess.IgnoreBatteryOptimizations,
            risk = AgentPermissionRisk.Sensitive,
        ),
        AgentPermissionCapability(
            id = "exact_alarm",
            title = "精确闹钟",
            description = "允许 Agent 未来按精确时间恢复任务。",
            specialAccess = AgentSpecialAccess.ExactAlarm,
            risk = AgentPermissionRisk.Sensitive,
        ),
        AgentPermissionCapability(
            id = "manage_all_files",
            title = "全文件访问",
            description = "高级实验权限。必须用户手动授权，且 Agent 只可访问设置中加入的外部路径。",
            specialAccess = AgentSpecialAccess.ManageAllFiles,
            risk = AgentPermissionRisk.High,
            minSdk = Build.VERSION_CODES.R,
            toolNames = listOf("external_file_list", "external_file_read", "external_file_write", "external_file_delete"),
        ),
        AgentPermissionCapability(
            id = "apps",
            title = "应用列表与启动",
            description = "读取可启动应用列表并打开指定应用。",
            risk = AgentPermissionRisk.Normal,
            toolNames = listOf("apps_list", "app_open", "app_info"),
        ),
        AgentPermissionCapability(
            id = "installed_apps_full_access",
            title = "全量应用列表",
            description = "高级实验能力。读取设备上更完整的已安装包列表；Google Play 会限制此权限。",
            risk = AgentPermissionRisk.High,
            debugOnly = true,
            toolNames = listOf("apps_installed_list"),
        ),
    )
}
