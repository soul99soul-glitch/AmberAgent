package app.amber.core.infra

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Single source of truth for Settings DataStore Preferences keys.
 *
 * Migrated from SettingsStore.Companion during M1.1.8e (god class deletion).
 * All keys preserve their original string identifiers — the on-disk DataStore
 * schema is unchanged.
 */
object PreferencesKeys {
    // 版本号
    val VERSION = intPreferencesKey("data_version")

    // UI设置
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val THEME_ID = stringPreferencesKey("theme_id")
    val DISPLAY_SETTING = stringPreferencesKey("display_setting")
    val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

    // 模型选择
    val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
    val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
    val SELECT_MODEL = stringPreferencesKey("chat_model")
    val TITLE_MODEL = stringPreferencesKey("title_model")
    val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
    val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
    val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
    val TITLE_PROMPT = stringPreferencesKey("title_prompt")
    val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
    val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
    val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
    val OCR_MODEL = stringPreferencesKey("ocr_model")
    val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
    val COMPRESS_MODEL = stringPreferencesKey("compress_model")
    val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
    val MODEL_GROUP_SESSION_DEFAULTS = stringPreferencesKey("model_group_session_defaults")

    // 提供商
    val PROVIDERS = stringPreferencesKey("providers")

    // 助手
    val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
    val ASSISTANTS = stringPreferencesKey("assistants")
    val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

    // 搜索
    val SEARCH_SERVICES = stringPreferencesKey("search_services")
    val SEARCH_COMMON = stringPreferencesKey("search_common")
    val SEARCH_SELECTED = intPreferencesKey("search_selected")
    val SEARCH_ENABLED_SERVICE_IDS = stringPreferencesKey("search_enabled_service_ids")
    val SEARCH_BUILTIN_DUCKDUCKGO_ENABLED = booleanPreferencesKey("search_builtin_duckduckgo_enabled")
    val SEARCH_BUILTIN_BING_ENABLED = booleanPreferencesKey("search_builtin_bing_enabled")
    val SEARCH_BUILTIN_JINA_ENABLED = booleanPreferencesKey("search_builtin_jina_enabled")
    val SEARCH_BUILTIN_WIKIPEDIA_ENABLED = booleanPreferencesKey("search_builtin_wikipedia_enabled")
    val SEARCH_BUILTIN_HACKERNEWS_ENABLED = booleanPreferencesKey("search_builtin_hackernews_enabled")
    val SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED = booleanPreferencesKey("search_google_webview_fallback_enabled")

    // MCP
    val MCP_SERVERS = stringPreferencesKey("mcp_servers")

    // WebDAV
    val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

    // S3
    val S3_CONFIG = stringPreferencesKey("s3_config")

    // TTS
    val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
    val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

    // Web Server
    val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
    val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
    val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
    val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
    val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

    // 提示词注入
    val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
    val LOREBOOKS = stringPreferencesKey("lorebooks")
    val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
    val AGENT_RUNTIME = stringPreferencesKey("agent_runtime")

    // 备份提醒
    val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

    // 同步与备份
    val SYNC_SETTINGS = stringPreferencesKey("sync_settings")

    // 统计
    val LAUNCH_COUNT = intPreferencesKey("launch_count")

    // [Review fix] One-shot flag so the per-load migration that seeds
    // gpt-image-2 / nano-banana-2 into the built-in OpenAI / Gemini
    // providers fires exactly once. Without this gate, a user who
    // intentionally deletes the seeded model gets it resurrected on
    // the next save flow.
    val SEEDED_IMAGE_MODELS_V1 = booleanPreferencesKey("seeded_image_models_v1")

    // Same one-shot pattern for the visual-routing slash commands
    // (/draw / /diagram / /slide). Seeded into settings.quickMessages
    // global pool and subscribed by every existing assistant on the
    // first load that sees this version <1.
    val SEEDED_ROUTING_QUICK_MESSAGES_V1 =
        booleanPreferencesKey("seeded_routing_quick_messages_v1")

    // 赞助提醒
    val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

    // -----------------------------------------------------------------------
    // Phase 2 Rust JNI production switches. All default to false so JVM stays
    // the default code path until a user opts in via Settings → Developer or
    // until Remote Config flips a cohort to enabled. See SPIKE_PLAN §8.3.
    // -----------------------------------------------------------------------
    val NATIVE_PATH_OFFICE = booleanPreferencesKey("native_path_office")
    val NATIVE_PATH_HIGHLIGHT = booleanPreferencesKey("native_path_highlight")
    val NATIVE_PATH_REGEX = booleanPreferencesKey("native_path_regex")
    val NATIVE_PATH_MARKDOWN_HTML = booleanPreferencesKey("native_path_markdown_html")
    val NATIVE_PATH_MARKDOWN_AST = booleanPreferencesKey("native_path_markdown_ast")
    /** Fraction of native calls that also run JVM for diff comparison. 0.0..1.0. */
    val NATIVE_PATH_SAMPLING_RATE = floatPreferencesKey("native_path_sampling_rate")
}
