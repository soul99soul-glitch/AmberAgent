package app.amber.feature.macgateway

import app.amber.agent.data.db.entity.ProactiveCardEntity
import kotlinx.serialization.Serializable

/**
 * Validation helpers and wire constants for Cloud Proactive v2 endpoints (`v2/proactive`)
 * and `amber://proactive/<signalId>` deep links.
 */
object ProactiveContract {
    const val MAX_CARD_TEXT_LENGTH = 4_000
    const val ACT_TEXT_FALLBACK_LIMIT = 600
    const val DELIVERY_MODE_IMMEDIATE = "immediate"
    const val DELIVERY_MODE_DIGEST = "digest"

    private val SIGNAL_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,48}$")
    private val GITHUB_OWNER_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
    private val GITHUB_REPO_REGEX = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val VALID_FEEDBACK_ACTIONS = ProactiveFeedbackAction.entries.map { it.wire }.toSet()

    fun isValidSignalId(signalId: String): Boolean = SIGNAL_ID_REGEX.matches(signalId)

    fun requireValidSignalId(signalId: String): String {
        val trimmed = signalId.trim()
        require(trimmed == signalId && isValidSignalId(trimmed)) {
            "invalid signalId"
        }
        return trimmed
    }

    fun requireValidFeedbackAction(action: String): String {
        val normalized = action.trim().lowercase()
        require(normalized in VALID_FEEDBACK_ACTIONS) {
            "invalid proactive feedback action: $action"
        }
        return normalized
    }

    fun normalizeDeliveryMode(mode: String): String {
        val normalized = mode.trim().lowercase()
        require(normalized == DELIVERY_MODE_IMMEDIATE || normalized == DELIVERY_MODE_DIGEST) {
            "deliveryMode must be immediate or digest"
        }
        return normalized
    }

    fun requireValidResourceId(id: String, fieldName: String = "id"): String {
        val trimmed = id.trim()
        require(
            trimmed.isNotEmpty() &&
                trimmed.length <= 128 &&
                !trimmed.contains('/') &&
                !trimmed.contains('\\') &&
                !trimmed.contains("..") &&
                trimmed.none { it.isISOControl() },
        ) {
            "invalid $fieldName"
        }
        return trimmed
    }

    /**
     * Validates GitHub `owner` and `repo` segments and builds the canonical Releases Atom feed URL.
     * Rejects `.`, `..`, schemes, slashes, queries, fragments, whitespace, and illegal characters.
     */
    fun buildGithubReleaseAtomUrl(owner: String, repo: String): Pair<String, String> {
        val cleanOwner = owner.trim()
        val cleanRepo = repo.trim()
        require(isValidGithubOwner(cleanOwner)) { "invalid github owner" }
        require(isValidGithubRepo(cleanRepo)) { "invalid github repo" }
        val title = "$cleanOwner/$cleanRepo Releases"
        val url = "https://github.com/$cleanOwner/$cleanRepo/releases.atom"
        return title to url
    }

    fun isValidGithubOwner(owner: String): Boolean {
        if (owner.isEmpty() || owner == "." || owner == "..") return false
        if (owner.contains("--")) return false
        return GITHUB_OWNER_REGEX.matches(owner)
    }

    fun isValidGithubRepo(repo: String): Boolean {
        if (repo.isEmpty() || repo == "." || repo == "..") return false
        if (repo.startsWith('.') || repo.endsWith('.') || repo.contains("..")) return false
        return GITHUB_REPO_REGEX.matches(repo)
    }
}

enum class ProactiveFeedbackAction(val wire: String) {
    OPENED("opened"),
    REPLIED("replied"),
    DISMISS("dismiss"),
    SNOOZE("snooze"),
    MUTE_TOPIC("mute_topic"),
    ;

    companion object {
        fun fromWire(value: String?): ProactiveFeedbackAction? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

@Serializable
data class CloudProactiveMutedTopic(
    val topicKey: String = "",
    val untilEpochMs: Long = 0L,
)

@Serializable
data class CloudProactiveRestraintSnapshot(
    val quietHoursEnabled: Boolean = true,
    val quietHoursStart: String = "23:00",
    val quietHoursEnd: String = "08:00",
    val zoneId: String = "Asia/Shanghai",
    val dailyMaxPushes: Int = 5,
    val pushesToday: Int = 0,
    val remainingToday: Int = 0,
    val bufferedPulseCount: Int = 0,
    val mutedTopicsCount: Int = 0,
    val pulseEnabled: Boolean = true,
    val pulseTime: String = "08:30",
    val commitmentExtractionEnabled: Boolean = true,
    val mutedTopics: List<CloudProactiveMutedTopic> = emptyList(),
)

@Serializable
data class CloudProactiveMonitorRecord(
    val id: String = "",
    val title: String = "",
    val url: String = "",
    val conditionPrompt: String = "",
    val intervalMinutes: Int = 15,
    val enabled: Boolean = true,
    val createdAtMs: Long = 0L,
    val lastCheckedAtMs: Long? = null,
    val lastOutcome: String? = null,
    val lastSummary: String? = null,
    val consecutiveUnchanged: Int = 0,
    val nextRunAtMs: Long = 0L,
    val deliveryMode: String = ProactiveContract.DELIVERY_MODE_IMMEDIATE,
)

@Serializable
data class CloudProactiveWebhookRecord(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val instruction: String = "",
    val createdAtMs: Long = 0L,
    val lastTriggeredAtMs: Long? = null,
    val triggerCount: Int = 0,
)

@Serializable
data class CloudProactiveRunHistoryEntry(
    val id: String = "",
    val signalId: String = "",
    val category: String = "",
    val topicKey: String = "",
    val title: String = "",
    /** Kept as raw String so unknown/future server outcomes render without failing decode. */
    val outcome: String = "",
    val summary: String = "",
    val timestampEpochMs: Long = 0L,
)

@Serializable
data class CloudProactiveWakeupRecord(
    val id: String = "",
    val kind: String = "agent",
    val title: String = "",
    val detail: String = "",
    val checkPrompt: String? = null,
    val triggerAtEpochMs: Long = 0L,
    val userRequested: Boolean = false,
    val originSignalId: String? = null,
    val sourceConversationId: String? = null,
    val status: String = "pending",
    val createdAtEpochMs: Long = 0L,
    val firedAtEpochMs: Long? = null,
)

@Serializable
data class CloudProactiveSummary(
    val monitors: List<CloudProactiveMonitorRecord> = emptyList(),
    val webhooks: List<CloudProactiveWebhookRecord> = emptyList(),
    val restraint: CloudProactiveRestraintSnapshot = CloudProactiveRestraintSnapshot(),
    val recentRuns: List<CloudProactiveRunHistoryEntry> = emptyList(),
    val wakeups: List<CloudProactiveWakeupRecord> = emptyList(),
)

@Serializable
data class CloudProactiveRestraintPatch(
    val quietHoursEnabled: Boolean? = null,
    val quietHoursStart: String? = null,
    val quietHoursEnd: String? = null,
    val dailyMaxPushes: Int? = null,
    val pulseEnabled: Boolean? = null,
    val pulseTime: String? = null,
    val commitmentExtractionEnabled: Boolean? = null,
)

@Serializable
data class ProactiveFeedbackResult(
    val ok: Boolean = true,
    val action: String = "",
    val applied: Boolean = true,
    val wakeupId: String? = null,
    val mutedUntilEpochMs: Long? = null,
)

/**
 * Domain model representing a persisted or fetched proactive card ready for UI / notification use.
 */
data class ProactiveCard(
    val gatewayId: String,
    val deviceId: String,
    val eventId: String,
    val signalId: String,
    val seq: Long,
    val text: String,
    val meta: ProactiveEventMeta,
    val receivedAt: Long,
    val notifiedAt: Long? = null,
    val feedbackState: String? = null,
) {
    fun buildActPrompt(): String {
        val explicit = meta.checkPrompt?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        return "关于这条主动提醒「${meta.title}」，请帮我处理：\n\n" +
            text.take(ProactiveContract.ACT_TEXT_FALLBACK_LIMIT)
    }

    companion object {
        fun fromEntityOrNull(entity: ProactiveCardEntity): ProactiveCard? {
            val meta = runCatching {
                MacGatewayJson.decodeFromString(ProactiveEventMeta.serializer(), entity.metaJson)
            }.getOrNull() ?: return null
            return ProactiveCard(
                gatewayId = entity.gatewayId,
                deviceId = entity.deviceId,
                eventId = entity.eventId,
                signalId = entity.signalId,
                seq = entity.seq,
                text = entity.text,
                meta = meta,
                receivedAt = entity.receivedAt,
                notifiedAt = entity.notifiedAt,
                feedbackState = entity.feedbackState,
            )
        }

        fun fromEventOrNull(
            event: CloudDialogueEvent,
            gatewayId: String,
            deviceId: String,
            receivedAt: Long,
        ): ProactiveCard? {
            val meta = event.proactive ?: return null
            if (!ProactiveContract.isValidSignalId(meta.signalId)) return null
            if (meta.topicKey.isBlank() || meta.category.isBlank() || meta.title.isBlank()) return null
            val resolvedEventId = event.id.ifBlank { "proactive:${meta.signalId}" }
            return ProactiveCard(
                gatewayId = gatewayId,
                deviceId = deviceId,
                eventId = resolvedEventId,
                signalId = meta.signalId,
                seq = event.seq,
                text = event.text.take(ProactiveContract.MAX_CARD_TEXT_LENGTH),
                meta = meta,
                receivedAt = receivedAt,
                notifiedAt = null,
                feedbackState = null,
            )
        }
    }
}

@Serializable
internal data class ProactiveSignalResponseDto(
    val event: CloudDialogueEvent? = null,
)

@Serializable
internal data class ProactiveRestraintEnvelopeDto(
    val restraint: CloudProactiveRestraintSnapshot,
)

@Serializable
internal data class ProactiveMonitorEnvelopeDto(
    val monitor: CloudProactiveMonitorRecord,
)

@Serializable
internal data class ProactiveWebhookEnvelopeDto(
    val webhook: CloudProactiveWebhookRecord,
)
