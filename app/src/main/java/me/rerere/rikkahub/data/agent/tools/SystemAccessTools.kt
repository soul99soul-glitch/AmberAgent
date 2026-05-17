package me.rerere.rikkahub.data.agent.tools

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.workspace.WorkspaceManager

/**
 * Thin coordinator for the 28 system-access tools. All per-tool logic lives
 * in sibling `XxxAccessTools.kt` factories under this package — this class
 * holds only the dependency wiring and the public `getTools()` registry.
 *
 * See `SystemAccessShared.kt` for the cross-domain helpers (trackSystemTool,
 * schema DSL, masking primitives) that every sibling consumes.
 */
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

    private val appsListTool by lazy { createAppsListTool(context, deps) }
    private val appOpenTool by lazy { createAppOpenTool(context, deps) }
    private val appsInstalledListTool by lazy { createAppsInstalledListTool(context, deps) }
    private val appInfoTool by lazy { createAppInfoTool(context, deps) }

    private val batteryStatusTool by lazy { createBatteryStatusTool(context, deps) }
    private val networkStatusTool by lazy { createNetworkStatusTool(context, deps) }
    private val wifiStatusTool by lazy { createWifiStatusTool(context, deps) }
    private val deviceInfoTool by lazy { createDeviceInfoTool(context, deps) }

    private val settingsOpenTool by lazy { createSettingsOpenTool(context, deps) }
    private val intentOpenTool by lazy { createIntentOpenTool(context, deps) }

    private val shareTextTool by lazy { createShareTextTool(context, deps) }
    private val shareFileTool by lazy { createShareFileTool(context, workspaceManager, deps) }

    private val notificationPostTool by lazy { createNotificationPostTool(context, deps) }
}
