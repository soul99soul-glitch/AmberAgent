package app.amber.feature.board

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.feature.board.hotlist.HotListProviderIds

/**
 * Default trigger anchors in 24h local time. Users can toggle these on/off individually.
 * 08:00 / 12:00 / 18:00 drive the board signal pipeline.
 * 13:00 / 19:00 additionally trigger the daily review generation windows.
 */
val DEFAULT_TODAY_BOARD_TRIGGER_HOURS = listOf("08:00", "12:00", "13:00", "18:00", "19:00")

/**
 * Cutoff hour for nightly cleanup. Items from previous days' boards are archived at this
 * hour local time so the next day's surface starts fresh. Chose 04:00 over midnight so
 * late-night users don't have their just-generated board wiped while they're still using
 * the app.
 */
const val TODAY_BOARD_DAY_CUTOFF_HOUR = 4

/** Signals scoring at or below this threshold are treated as hard-muted. */
const val TODAY_BOARD_HARD_MUTE_WEIGHT = -10

/** Number of consecutive dismisses before auto-mute kicks in. */
const val TODAY_BOARD_AUTO_MUTE_DISMISS_COUNT = 3

val DEFAULT_HOT_LIST_FOCUS_KEYWORDS = listOf(
    "AI",
    "人工智能",
    "大模型",
    "LLM",
    "Agent",
    "机器人",
    "具身智能",
    "自动驾驶",
    "数码",
    "3C",
    "智能硬件",
    "芯片",
    "半导体",
    "OpenAI",
    "Claude",
    "DeepSeek",
    "Gemini",
    "NVIDIA",
    "小米",
    "华为",
    "特斯拉",
)

@Serializable
enum class TodayBoardDensity(val wireName: String) {
    @SerialName("compact")
    COMPACT("compact"),

    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("rich")
    RICH("rich");

    companion object {
        fun fromWireName(raw: String?): TodayBoardDensity =
            entries.firstOrNull { it.wireName == raw } ?: STANDARD
    }
}

/** Controls how the scheduler behaves when device constraints change. */
@Serializable
enum class TodayBoardBackgroundStrategy(val wireName: String) {
    /** WiFi + battery not low, scheduled runs still fire on cellular. Default. */
    @SerialName("smart")
    SMART("smart"),

    @SerialName("wifi_only")
    WIFI_ONLY("wifi_only"),

    /** Board only refreshes while app is in the foreground — useful for privacy-minded users. */
    @SerialName("foreground_only")
    FOREGROUND_ONLY("foreground_only");

    companion object {
        fun fromWireName(raw: String?): TodayBoardBackgroundStrategy =
            entries.firstOrNull { it.wireName == raw } ?: SMART
    }
}

@Serializable
enum class TodayBoardHotListFilterMode(val wireName: String) {
    @SerialName("all")
    ALL("all"),

    @SerialName("focus_first")
    FOCUS_FIRST("focus_first"),

    @SerialName("focus_only")
    FOCUS_ONLY("focus_only");

    companion object {
        fun fromWireName(raw: String?): TodayBoardHotListFilterMode =
            entries.firstOrNull { it.wireName == raw } ?: FOCUS_FIRST
    }
}

@Serializable
enum class TodayBoardReadingFontMode(val wireName: String) {
    @SerialName("system")
    SYSTEM("system"),

    @SerialName("serif")
    SERIF("serif"),

    @SerialName("slides_pack")
    SLIDES_PACK("slides_pack");

    companion object {
        fun fromWireName(raw: String?): TodayBoardReadingFontMode =
            entries.firstOrNull { it.wireName == raw } ?: SERIF
    }
}

object DeepReadTemplateIds {
    const val COMPOSE_MAGAZINE = "compose_magazine"
    const val EDITORIAL_SLANT = "editorial_slant"
    const val CUSTOM_PREFIX = "custom:"

    fun custom(id: String): String = if (id.startsWith(CUSTOM_PREFIX)) id else "$CUSTOM_PREFIX$id"
}

const val DEEP_READ_FONT_SCALE_MIN = 0.85f
const val DEEP_READ_FONT_SCALE_MAX = 1.25f
const val DEEP_READ_FONT_SCALE_STEP = 0.05f

/**
 * Canonical source types for BoardSignalEntity.sourceType. Kept as string constants rather
 * than an enum to allow future collectors (mail / github / rss) to be added without DB
 * schema churn — the column stays TEXT.
 */
object BoardSignalSourceType {
    const val NOTIFICATION = "notification"
    const val CALENDAR = "calendar"
    const val FEISHU_MSG = "feishu_msg"
    const val FEISHU_DOC = "feishu_doc"
    const val CHAT_HISTORY = "chat_history"
    const val TIME = "time"

    val MVP_SOURCES: Set<String> = setOf(
        NOTIFICATION,
        CALENDAR,
        FEISHU_MSG,
        FEISHU_DOC,
        CHAT_HISTORY,
        TIME,
    )
}

/**
 * All data-source toggles and trigger policy live here. Intentionally flat and small —
 * we'd rather ship a thin config surface and extend it as features land than pre-bake a
 * settings UI that mostly sits empty.
 *
 * [boardModelId] is nullable on purpose: when null the board inherits the user's main
 * chat model, mirroring the SubAgent "follow main model" convention. Setting a specific
 * model id here lets privacy / cost-sensitive users route board runs to a cheaper model.
 *
 * [enabledSources] uses BoardSignalSourceType string keys. New sources added later don't
 * require a schema migration — default-off via absence from the set.
 */
@Serializable
data class TodayBoardSetting(
    val enabled: Boolean = false,
    val boardModelId: String? = null,
    val enabledSources: Set<String> = BoardSignalSourceType.MVP_SOURCES,
    val triggerHours: List<String> = DEFAULT_TODAY_BOARD_TRIGGER_HOURS,
    val incrementalSignalThreshold: Int = 5,
    val hotListRefreshIntervalMinutes: Int = 60,
    val hotListWifiOnly: Boolean = false,
    val hotListEnabledSources: Set<String> = HotListProviderIds.DEFAULT_ENABLED,
    val hotListFocusKeywords: List<String> = DEFAULT_HOT_LIST_FOCUS_KEYWORDS,
    val hotListFilterMode: TodayBoardHotListFilterMode = TodayBoardHotListFilterMode.FOCUS_FIRST,
    val deepReadFirstUseConfirmed: Boolean = false,
    val boardReadingFontMode: TodayBoardReadingFontMode = TodayBoardReadingFontMode.SERIF,
    val boardReadingFontPackId: String? = null,
    val deepReadFontScale: Float = 1.0f,
    val deepReadTemplateId: String = DeepReadTemplateIds.COMPOSE_MAGAZINE,
    val density: TodayBoardDensity = TodayBoardDensity.STANDARD,
    val backgroundStrategy: TodayBoardBackgroundStrategy = TodayBoardBackgroundStrategy.SMART,
    val foregroundCompensationGapMs: Long = 2 * 60 * 60 * 1000L,
)
