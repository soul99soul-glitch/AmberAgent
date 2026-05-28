package app.amber.core.memory.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

// MemoryScope and MemoryKind moved to app.amber.core.model.MemoryEnums so
// :core:model can be physically extracted without pulling memory subsystem.
typealias MemoryScope = app.amber.core.model.MemoryScope
typealias MemoryKind = app.amber.core.model.MemoryKind

@Serializable
enum class MemoryCandidateStatus(val wireName: String) {
    @SerialName("pending")
    PENDING("pending"),

    @SerialName("accepted")
    ACCEPTED("accepted"),

    @SerialName("ignored")
    IGNORED("ignored"),

    @SerialName("filtered")
    FILTERED("filtered");

    companion object {
        fun fromWireName(value: String?): MemoryCandidateStatus =
            entries.firstOrNull { it.wireName == value } ?: PENDING
    }
}

@Serializable
enum class MemoryEventType(val wireName: String) {
    @SerialName("extraction_started")
    EXTRACTION_STARTED("extraction_started"),

    @SerialName("extraction_skipped")
    EXTRACTION_SKIPPED("extraction_skipped"),

    @SerialName("candidate_created")
    CANDIDATE_CREATED("candidate_created"),

    @SerialName("candidate_accepted")
    CANDIDATE_ACCEPTED("candidate_accepted"),

    @SerialName("memory_created")
    MEMORY_CREATED("memory_created"),

    @SerialName("memory_updated")
    MEMORY_UPDATED("memory_updated"),

    @SerialName("memory_archived")
    MEMORY_ARCHIVED("memory_archived"),

    @SerialName("memory_expired")
    MEMORY_EXPIRED("memory_expired"),

    @SerialName("extraction_failed")
    EXTRACTION_FAILED("extraction_failed"),

    @SerialName("dream_planned")
    DREAM_PLANNED("dream_planned"),

    @SerialName("dream_applied")
    DREAM_APPLIED("dream_applied"),

    @SerialName("dream_failed")
    DREAM_FAILED("dream_failed");
}

@Serializable
data class MemoryRecord(
    val id: Int,
    val content: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val assistantId: String,
    val sourceConversationId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val expiresAt: Long? = null,
    val confidence: Float = 1f,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastUsedAt: Long? = null,
)

@Serializable
data class MemoryCandidate(
    val id: String = Uuid.random().toString(),
    val content: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val sourceConversationId: String? = null,
    val sourceMessageIds: List<String> = emptyList(),
    val expiresAt: Long? = null,
    val confidence: Float = 0.5f,
    val reason: String = "",
    val sensitive: Boolean = false,
    val status: MemoryCandidateStatus = MemoryCandidateStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class MemoryEvent(
    val id: String = Uuid.random().toString(),
    val type: MemoryEventType,
    val conversationId: String? = null,
    val memoryId: Int? = null,
    val candidateId: String? = null,
    val modelId: String? = null,
    val message: String = "",
    val durationMs: Long? = null,
    val messageCount: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class MemoryRecallSetting(
    val maxItems: Int = 12,
    val maxPromptChars: Int = 2_000,
    val debug: Boolean = false,
)

@Serializable
data class MemoryWorkerSetting(
    val enabled: Boolean = true,
    val modelId: Uuid = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08"),
    val followCompressModel: Boolean = true,
    val daydreamModelId: Uuid = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08"),
    val daydreamFollowCompressModel: Boolean = true,
    val daydreamReasoningLevel: ReasoningLevel = ReasoningLevel.HIGH,
    val extractionEnabled: Boolean = true,
    val dreamEnabled: Boolean = false,
    val runOnlyOnIdle: Boolean = true,
    val runOnlyOnCharging: Boolean = true,
    val maxDailyRuns: Int = 8,
    val dreamMaxDailyRuns: Int = 1,
    val timeoutMs: Long = 120_000L,
)
