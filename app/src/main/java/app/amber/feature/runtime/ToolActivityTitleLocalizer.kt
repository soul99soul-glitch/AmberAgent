package app.amber.feature.runtime

import android.content.Context
import app.amber.agent.R
import org.json.JSONObject

/**
 * App-layer presentation mapping for sandbox activity titles. Producers keep
 * their stable wire tool names and raw titles; this maps only known operation
 * ids to Android resources and keeps safe path/query/app details in the UI.
 */
class ToolActivityTitleLocalizer(
    private val context: Context,
) : ToolActivityTitleResolver {
    override fun resolve(toolName: String, rawTitle: String, inputPreview: String): String {
        if (toolName.startsWith("mcp__")) return title(R.string.tool_activity_mcp_call)
        val input = inputPreview.asJsonObject()
        return when (toolName) {
            "apps_list" -> title(R.string.tool_activity_apps_list, input.value("query"))
            "app_open" -> title(R.string.tool_activity_app_open, input.value("package_name"))
            "apps_installed_list" -> title(R.string.tool_activity_apps_installed_list, input.value("query"))
            "app_info" -> title(R.string.tool_activity_app_info, input.value("package_name"))
            "audio_record_once" -> title(R.string.tool_activity_audio_record_once)
            "calendar_list" -> title(R.string.tool_activity_calendar_list)
            "calendar_create" -> title(R.string.tool_activity_calendar_create)
            "contacts_search" -> title(R.string.tool_activity_contacts_search, input.value("query"))
            "contacts_write" -> title(R.string.tool_activity_contacts_write, input.value("name"))
            "battery_status" -> title(R.string.tool_activity_battery_status)
            "network_status" -> title(R.string.tool_activity_network_status)
            "wifi_status" -> title(R.string.tool_activity_wifi_status)
            "device_info" -> title(R.string.tool_activity_device_info)
            "settings_open" -> title(R.string.tool_activity_settings_open, input.value("target"))
            "intent_open" -> title(R.string.tool_activity_intent_open, input.value("data_uri", "action"))
            "location_current" -> title(R.string.tool_activity_location_current)
            "media_search" -> title(R.string.tool_activity_media_search, input.value("query"))
            "device_phone_state" -> title(R.string.tool_activity_device_phone_state)
            "call_log_list" -> title(R.string.tool_activity_call_log_list)
            "call_phone" -> title(R.string.tool_activity_call_phone)
            "sms_list" -> title(R.string.tool_activity_sms_list)
            "sms_read" -> title(R.string.tool_activity_sms_read)
            "sms_send" -> title(R.string.tool_activity_sms_send)
            "usage_stats_list" -> title(R.string.tool_activity_usage_stats_list)
            "notification_list" -> title(R.string.tool_activity_notification_list)
            "notification_post" -> title(R.string.tool_activity_notification_post)
            "share_text" -> title(R.string.tool_activity_share_text)
            "share_file" -> title(R.string.tool_activity_share_file, input.value("path"))

            "file_list" -> title(R.string.tool_activity_file_list, input.value("path"))
            "file_read" -> title(R.string.tool_activity_file_read, input.value("path"))
            "file_write" -> title(R.string.tool_activity_file_write, input.value("path"))
            "file_edit" -> title(R.string.tool_activity_file_edit, input.value("path"))
            "file_search" -> title(R.string.tool_activity_file_search, input.value("query"))
            "file_move" -> title(R.string.tool_activity_file_move, input.pathPair("source_path", "target_path"))

            "terminal_execute" -> title(R.string.tool_activity_terminal_execute, input.commandOr(inputPreview))
            "terminal_job_start" -> title(R.string.tool_activity_terminal_job_start, input.commandOr(inputPreview))
            "terminal_job_read" -> title(R.string.tool_activity_terminal_job_read)
            "terminal_job_wait" -> title(R.string.tool_activity_terminal_job_wait)
            "terminal_job_stop" -> title(R.string.tool_activity_terminal_job_stop)
            "terminal_install_packages" -> title(R.string.tool_activity_terminal_install_packages, input.commandOr(inputPreview))
            "terminal_workspace_flush" -> title(R.string.tool_activity_terminal_workspace_flush)
            "terminal_session_start" -> title(R.string.tool_activity_terminal_session_start)
            "terminal_session_exec" -> title(R.string.tool_activity_terminal_session_exec, input.value("command"))
            "terminal_session_read" -> title(R.string.tool_activity_terminal_session_read)
            "terminal_session_stop" -> title(R.string.tool_activity_terminal_session_stop)

            "external_file_list" -> title(R.string.tool_activity_external_file_list, input.value("path"))
            "external_file_read" -> title(R.string.tool_activity_external_file_read, input.value("path"))
            "external_file_write" -> title(R.string.tool_activity_external_file_write, input.value("path"))
            "external_file_delete" -> title(R.string.tool_activity_external_file_delete, input.value("path"))

            "icloud_list" -> title(R.string.tool_activity_icloud_list, input.value("path"))
            "icloud_stat" -> title(R.string.tool_activity_icloud_stat, input.value("path", "node_ref"))
            "icloud_read" -> title(R.string.tool_activity_icloud_read, input.value("path", "node_ref"))
            "icloud_write" -> title(R.string.tool_activity_icloud_write, input.value("path"))
            "icloud_search" -> title(R.string.tool_activity_icloud_search, input.value("query"))

            "screen_click" -> title(R.string.tool_activity_screen_click)
            "screen_long_click" -> title(R.string.tool_activity_screen_long_click)
            "screen_swipe" -> title(R.string.tool_activity_screen_swipe)
            "screen_input_text" -> title(R.string.tool_activity_screen_input_text)
            "screen_back" -> title(R.string.tool_activity_screen_back)
            "screen_home" -> title(R.string.tool_activity_screen_home)
            "screen_open_app" -> title(R.string.tool_activity_screen_open_app, input.value("package_name"))
            "screen_open_url" -> title(R.string.tool_activity_screen_open_url, input.value("url"))
            "screen_read_ui" -> title(R.string.tool_activity_screen_read_ui)
            "screen_find_text" -> title(R.string.tool_activity_screen_find_text, input.value("text"))
            "screen_tap_text" -> title(R.string.tool_activity_screen_tap_text, input.value("text"))
            "screen_wait_for_text" -> title(R.string.tool_activity_screen_wait_for_text, input.value("text"))
            "screen_scroll_until" -> title(R.string.tool_activity_screen_scroll_until, input.value("text"))
            "screen_screenshot" -> title(R.string.tool_activity_screen_screenshot)
            "vlm_task" -> title(R.string.tool_activity_vlm_task)

            "http_request" -> title(R.string.tool_activity_http_request, input.value("url"))
            "download_file" -> title(R.string.tool_activity_download_file, input.value("workspace_path", "url"))
            "archive_list" -> title(R.string.tool_activity_archive_list, input.value("path"))
            "archive_extract" -> title(R.string.tool_activity_archive_extract, input.pathPair("path", "destination_path"))
            "archive_create" -> title(R.string.tool_activity_archive_create, input.value("destination_path"))
            "pdf_read" -> title(R.string.tool_activity_pdf_read, input.value("path"))
            "pdf_render_page" -> title(R.string.tool_activity_pdf_render_page, input.value("path"))
            "office_read" -> title(R.string.tool_activity_office_read, input.value("path"))
            "image_info" -> title(R.string.tool_activity_image_info, input.value("path"))
            "image_convert" -> title(R.string.tool_activity_image_convert, input.pathPair("path", "destination_path"))
            "ocr_image" -> title(R.string.tool_activity_ocr_image, input.value("path"))

            "officepro_capture_context" -> title(R.string.tool_activity_officepro_capture_context)
            "officepro_context_digest" -> title(R.string.tool_activity_officepro_context_digest)
            "officepro_create_base_record_draft" -> title(R.string.tool_activity_officepro_create_base_record_draft)
            "officepro_create_task_draft" -> title(R.string.tool_activity_officepro_create_task_draft)
            "officepro_daily_radar" -> title(R.string.tool_activity_officepro_daily_radar)
            "officepro_dashboard" -> title(R.string.tool_activity_officepro_dashboard)
            "officepro_document_warroom" -> title(R.string.tool_activity_officepro_document_warroom)
            "officepro_make_report" -> title(R.string.tool_activity_officepro_make_report)
            "officepro_meeting_closure" -> title(R.string.tool_activity_officepro_meeting_closure)
            "officepro_open" -> title(R.string.tool_activity_officepro_open)
            "officepro_open_items_radar" -> title(R.string.tool_activity_officepro_open_items_radar)
            "officepro_project_briefing" -> title(R.string.tool_activity_officepro_project_briefing)
            "officepro_project_context" -> title(R.string.tool_activity_officepro_project_context)
            "officepro_project_report" -> title(R.string.tool_activity_officepro_project_report)
            "officepro_project_update" -> title(R.string.tool_activity_officepro_project_update)
            "officepro_read_screen" -> title(R.string.tool_activity_officepro_read_screen)
            "officepro_reply_draft" -> title(R.string.tool_activity_officepro_reply_draft)
            "officepro_search" -> title(R.string.tool_activity_officepro_search)

            "bilibili_hot_videos" -> title(R.string.tool_activity_bilibili_hot_videos)
            "bilibili_search" -> title(R.string.tool_activity_bilibili_search)
            "bilibili_user_history" -> title(R.string.tool_activity_bilibili_user_history)
            "bilibili_video_info" -> title(R.string.tool_activity_bilibili_video_info)
            "feishu_docs_append_block" -> title(R.string.tool_activity_feishu_docs_append_block)
            "feishu_docs_append_callout" -> title(R.string.tool_activity_feishu_docs_append_callout)
            "feishu_docs_append_heading" -> title(R.string.tool_activity_feishu_docs_append_heading)
            "feishu_docs_append_list_item" -> title(R.string.tool_activity_feishu_docs_append_list_item)
            "feishu_docs_blocks" -> title(R.string.tool_activity_feishu_docs_blocks)
            "feishu_docs_create" -> title(R.string.tool_activity_feishu_docs_create)
            "feishu_docs_list" -> title(R.string.tool_activity_feishu_docs_list)
            "feishu_docs_markdown_pack" -> title(R.string.tool_activity_feishu_docs_markdown_pack)
            "feishu_docs_network_summary" -> title(R.string.tool_activity_feishu_docs_network_summary)
            "feishu_docs_read" -> title(R.string.tool_activity_feishu_docs_read)
            "feishu_docs_resolve" -> title(R.string.tool_activity_feishu_docs_resolve)
            "feishu_docs_search" -> title(R.string.tool_activity_feishu_docs_search)
            "feishu_docs_snapshot" -> title(R.string.tool_activity_feishu_docs_snapshot)
            "github_file_read" -> title(R.string.tool_activity_github_file_read)
            "github_issue_list" -> title(R.string.tool_activity_github_issue_list)
            "github_pr_list" -> title(R.string.tool_activity_github_pr_list)
            "github_repo_read" -> title(R.string.tool_activity_github_repo_read)
            "github_repo_search" -> title(R.string.tool_activity_github_repo_search)
            "github_user_read" -> title(R.string.tool_activity_github_user_read)
            "hn_item_read" -> title(R.string.tool_activity_hn_item_read)
            "hn_search" -> title(R.string.tool_activity_hn_search)
            "hn_top" -> title(R.string.tool_activity_hn_top)
            "hn_user_read" -> title(R.string.tool_activity_hn_user_read)
            "juejin_article_read" -> title(R.string.tool_activity_juejin_article_read)
            "juejin_feed" -> title(R.string.tool_activity_juejin_feed)
            "juejin_my_posts" -> title(R.string.tool_activity_juejin_my_posts)
            "juejin_pins" -> title(R.string.tool_activity_juejin_pins)
            "juejin_search" -> title(R.string.tool_activity_juejin_search)
            "reddit_post_read" -> title(R.string.tool_activity_reddit_post_read)
            "reddit_search" -> title(R.string.tool_activity_reddit_search)
            "reddit_subreddit_read" -> title(R.string.tool_activity_reddit_subreddit_read)
            "reddit_top" -> title(R.string.tool_activity_reddit_top)
            "wm_back" -> title(R.string.tool_activity_wm_back)
            "wm_click" -> title(R.string.tool_activity_wm_click)
            "wm_eval" -> title(R.string.tool_activity_wm_eval)
            "wm_extract" -> title(R.string.tool_activity_wm_extract)
            "wm_fetch_replay" -> title(R.string.tool_activity_wm_fetch_replay)
            "wm_find" -> title(R.string.tool_activity_wm_find)
            "wm_forward" -> title(R.string.tool_activity_wm_forward)
            "wm_get" -> title(R.string.tool_activity_wm_get)
            "wm_keys" -> title(R.string.tool_activity_wm_keys)
            "wm_network_inspect" -> title(R.string.tool_activity_wm_network_inspect)
            "wm_observe" -> title(R.string.tool_activity_wm_observe)
            "wm_open" -> title(R.string.tool_activity_wm_open)
            "wm_profile_synthesize" -> title(R.string.tool_activity_wm_profile_synthesize)
            "wm_recipe_candidates" -> title(R.string.tool_activity_wm_recipe_candidates)
            "wm_screenshot" -> title(R.string.tool_activity_wm_screenshot)
            "wm_scroll" -> title(R.string.tool_activity_wm_scroll)
            "wm_select" -> title(R.string.tool_activity_wm_select)
            "wm_signed_fetch" -> title(R.string.tool_activity_wm_signed_fetch)
            "wm_site_add" -> title(R.string.tool_activity_wm_site_add)
            "wm_site_remove" -> title(R.string.tool_activity_wm_site_remove)
            "wm_state" -> title(R.string.tool_activity_wm_state)
            "wm_stations" -> title(R.string.tool_activity_wm_stations)
            "wm_tab_close" -> title(R.string.tool_activity_wm_tab_close)
            "wm_tab_list" -> title(R.string.tool_activity_wm_tab_list)
            "wm_tab_new" -> title(R.string.tool_activity_wm_tab_new)
            "wm_tap" -> title(R.string.tool_activity_wm_tap)
            "wm_type" -> title(R.string.tool_activity_wm_type)
            "wm_visual_read" -> title(R.string.tool_activity_wm_visual_read)
            "wm_visual_snapshot" -> title(R.string.tool_activity_wm_visual_snapshot)
            "wm_wait" -> title(R.string.tool_activity_wm_wait)
            "zhihu_answer_read" -> title(R.string.tool_activity_zhihu_answer_read)
            "zhihu_feed" -> title(R.string.tool_activity_zhihu_feed)
            "zhihu_question_read" -> title(R.string.tool_activity_zhihu_question_read)
            "zhihu_search" -> title(R.string.tool_activity_zhihu_search)

            else -> rawTitle
        }
    }

    private fun title(resourceId: Int, detail: String? = null): String {
        val base = context.getString(resourceId)
        val safeDetail = detail?.trim()?.takeIf(String::isNotEmpty)
        return if (safeDetail == null) {
            base
        } else {
            context.getString(R.string.tool_activity_with_detail, base, safeDetail)
        }
    }

    private fun String.asJsonObject(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

    private fun JSONObject?.value(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        this?.optString(name)?.takeUnless { it == "null" }?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun JSONObject?.pathPair(first: String, second: String): String? {
        val firstValue = value(first)
        val secondValue = value(second)
        return when {
            firstValue != null && secondValue != null -> "$firstValue → $secondValue"
            else -> firstValue ?: secondValue
        }
    }

    private fun JSONObject?.commandOr(inputPreview: String): String? =
        value("command") ?: inputPreview.takeIf { this == null && it.isNotBlank() }
}
