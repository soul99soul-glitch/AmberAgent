package app.amber.core.localization

import android.content.Context
import app.amber.agent.R
import app.amber.feature.system.AgentPermissionCapability
import app.amber.feature.tools.Capability

/** Resolves static permission and capability copy in the app's current locale. */
object PermissionDisplayLocalizer {
    fun title(context: Context, capability: AgentPermissionCapability): String =
        permissionCopies[capability.id]?.let { context.getString(it.titleRes) } ?: capability.title

    fun description(context: Context, capability: AgentPermissionCapability): String =
        permissionCopies[capability.id]?.let { context.getString(it.descriptionRes) } ?: capability.description

    fun capabilityLabel(context: Context, capability: Capability): String =
        capabilityLabels[capability]?.let { context.getString(it) } ?: capability.label

    fun grantGuidance(context: Context, capability: AgentPermissionCapability): String =
        context.getString(R.string.permission_display_grant_guidance, title(context, capability))

    private data class PermissionCopy(val titleRes: Int, val descriptionRes: Int)

    private val permissionCopies = mapOf(
        "contacts_read" to PermissionCopy(
            titleRes = R.string.permission_display_contacts_read_title,
            descriptionRes = R.string.permission_display_contacts_read_description,
        ),
        "contacts_write" to PermissionCopy(
            titleRes = R.string.permission_display_contacts_write_title,
            descriptionRes = R.string.permission_display_contacts_write_description,
        ),
        "sms_read" to PermissionCopy(
            titleRes = R.string.permission_display_sms_read_title,
            descriptionRes = R.string.permission_display_sms_read_description,
        ),
        "sms_send" to PermissionCopy(
            titleRes = R.string.permission_display_sms_send_title,
            descriptionRes = R.string.permission_display_sms_send_description,
        ),
        "phone_state" to PermissionCopy(
            titleRes = R.string.permission_display_phone_state_title,
            descriptionRes = R.string.permission_display_phone_state_description,
        ),
        "call_log_read" to PermissionCopy(
            titleRes = R.string.permission_display_call_log_read_title,
            descriptionRes = R.string.permission_display_call_log_read_description,
        ),
        "call_phone" to PermissionCopy(
            titleRes = R.string.permission_display_call_phone_title,
            descriptionRes = R.string.permission_display_call_phone_description,
        ),
        "calendar_read" to PermissionCopy(
            titleRes = R.string.permission_display_calendar_read_title,
            descriptionRes = R.string.permission_display_calendar_read_description,
        ),
        "calendar_write" to PermissionCopy(
            titleRes = R.string.permission_display_calendar_write_title,
            descriptionRes = R.string.permission_display_calendar_write_description,
        ),
        "media_images" to PermissionCopy(
            titleRes = R.string.permission_display_media_images_title,
            descriptionRes = R.string.permission_display_media_images_description,
        ),
        "media_video" to PermissionCopy(
            titleRes = R.string.permission_display_media_video_title,
            descriptionRes = R.string.permission_display_media_video_description,
        ),
        "media_audio" to PermissionCopy(
            titleRes = R.string.permission_display_media_audio_title,
            descriptionRes = R.string.permission_display_media_audio_description,
        ),
        "location_current" to PermissionCopy(
            titleRes = R.string.permission_display_location_current_title,
            descriptionRes = R.string.permission_display_location_current_description,
        ),
        "audio_record" to PermissionCopy(
            titleRes = R.string.permission_display_audio_record_title,
            descriptionRes = R.string.permission_display_audio_record_description,
        ),
        "nearby_devices" to PermissionCopy(
            titleRes = R.string.permission_display_nearby_devices_title,
            descriptionRes = R.string.permission_display_nearby_devices_description,
        ),
        "activity_recognition" to PermissionCopy(
            titleRes = R.string.permission_display_activity_recognition_title,
            descriptionRes = R.string.permission_display_activity_recognition_description,
        ),
        "notification_access" to PermissionCopy(
            titleRes = R.string.permission_display_notification_access_title,
            descriptionRes = R.string.permission_display_notification_access_description,
        ),
        "usage_access" to PermissionCopy(
            titleRes = R.string.permission_display_usage_access_title,
            descriptionRes = R.string.permission_display_usage_access_description,
        ),
        "overlay" to PermissionCopy(
            titleRes = R.string.permission_display_overlay_title,
            descriptionRes = R.string.permission_display_overlay_description,
        ),
        "battery_optimization" to PermissionCopy(
            titleRes = R.string.permission_display_battery_optimization_title,
            descriptionRes = R.string.permission_display_battery_optimization_description,
        ),
        "exact_alarm" to PermissionCopy(
            titleRes = R.string.permission_display_exact_alarm_title,
            descriptionRes = R.string.permission_display_exact_alarm_description,
        ),
        "manage_all_files" to PermissionCopy(
            titleRes = R.string.permission_display_manage_all_files_title,
            descriptionRes = R.string.permission_display_manage_all_files_description,
        ),
        "apps" to PermissionCopy(
            titleRes = R.string.permission_display_apps_title,
            descriptionRes = R.string.permission_display_apps_description,
        ),
        "installed_apps_full_access" to PermissionCopy(
            titleRes = R.string.permission_display_installed_apps_full_access_title,
            descriptionRes = R.string.permission_display_installed_apps_full_access_description,
        ),
    )

    private val capabilityLabels = mapOf(
        Capability.FILESYSTEM_READ to R.string.capability_display_filesystem_read_label,
        Capability.FILESYSTEM_WRITE to R.string.capability_display_filesystem_write_label,
        Capability.WORKSPACE_DELETE to R.string.capability_display_workspace_delete_label,
        Capability.NETWORK_CONNECT to R.string.capability_display_network_connect_label,
        Capability.MCP_IMPORT to R.string.capability_display_mcp_import_label,
        Capability.MCP_TOOL to R.string.capability_display_mcp_tool_label,
        Capability.SKILL_PROMOTE to R.string.capability_display_skill_promote_label,
        Capability.SOUL_UPDATE to R.string.capability_display_soul_update_label,
        Capability.MINIAPP_SEND to R.string.capability_display_miniapp_send_label,
        Capability.RECIPE_IMPORT to R.string.capability_display_recipe_import_label,
        Capability.PROVIDER_CONFIG to R.string.capability_display_provider_config_label,
        Capability.SMS_READ to R.string.capability_display_sms_read_label,
        Capability.SMS_SEND to R.string.capability_display_sms_send_label,
        Capability.CALL_LOG_READ to R.string.capability_display_calllog_read_label,
        Capability.CALL_PHONE to R.string.capability_display_call_phone_label,
        Capability.CONTACTS_READ to R.string.capability_display_contacts_read_label,
        Capability.CONTACTS_WRITE to R.string.capability_display_contacts_write_label,
        Capability.LOCATION_CURRENT to R.string.capability_display_location_current_label,
        Capability.AUDIO_RECORD to R.string.capability_display_audio_record_label,
        Capability.SCREEN_CAPTURE to R.string.capability_display_screen_capture_label,
        Capability.CLIPBOARD_ACCESS to R.string.capability_display_clipboard_access_label,
    )
}
