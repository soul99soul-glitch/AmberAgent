package app.amber.feature.board

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val DEFAULT_TODAY_BOARD_TRIGGER_HOURS = listOf("08:00", "12:00", "13:00", "18:00", "19:00")

/** Signals scoring at or below this threshold are treated as hard-muted. */
const val TODAY_BOARD_HARD_MUTE_WEIGHT = -10

private val DEFAULT_HOT_LIST_FOCUS_KEYWORDS = listOf(
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

/** Serialized settings retained for existing installations and backups. */
@Serializable
data class TodayBoardSetting(
    val enabled: Boolean = false,
    val boardModelId: String? = null,
    val enabledSources: Set<String> = setOf("notification", "calendar", "feishu_msg", "chat_history", "time"),
    val triggerHours: List<String> = DEFAULT_TODAY_BOARD_TRIGGER_HOURS,
    val incrementalSignalThreshold: Int = 5,
    val hotListRefreshIntervalMinutes: Int = 60,
    val hotListWifiOnly: Boolean = false,
    val hotListEnabledSources: Set<String> = setOf("bilibili", "hacker_news"),
    val hotListFocusKeywords: List<String> = DEFAULT_HOT_LIST_FOCUS_KEYWORDS,
    val hotListFilterMode: TodayBoardHotListFilterMode = TodayBoardHotListFilterMode.FOCUS_FIRST,
    val deepReadFirstUseConfirmed: Boolean = false,
    /**
     * Deep Read 缓存有效期（天）。0 = 永不过期（仅手动删除）。
     * 默认 7 天，对齐杂志档案定位。
     */
    val deepReadCacheTtlDays: Int = 7,
    val boardReadingFontMode: TodayBoardReadingFontMode = TodayBoardReadingFontMode.SERIF,
    val boardReadingFontPackId: String? = null,
    val deepReadFontScale: Float = 1.0f,
    val deepReadTemplateId: String = "compose_magazine",
    val deepReadAccent: String = "cinnabar",
    val density: TodayBoardDensity = TodayBoardDensity.STANDARD,
    val backgroundStrategy: TodayBoardBackgroundStrategy = TodayBoardBackgroundStrategy.SMART,
    val foregroundCompensationGapMs: Long = 2 * 60 * 60 * 1000L,
)
