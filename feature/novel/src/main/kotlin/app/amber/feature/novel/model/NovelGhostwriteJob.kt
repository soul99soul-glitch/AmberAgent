package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import app.amber.feature.novel.serialization.NovelTypedIdSerializer
import app.amber.feature.novel.serialization.normalizeUuidString
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import app.amber.feature.novel.serialization.uuidStringLower
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable(with = NovelGhostwriteJobId.Serializer::class)
@JvmInline
value class NovelGhostwriteJobId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelGhostwriteJobId =
            NovelGhostwriteJobId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelGhostwriteJobId =
            NovelGhostwriteJobId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelGhostwriteJobId>(
        serialName = "NovelGhostwriteJobId",
        wrap = ::NovelGhostwriteJobId,
        unwrap = { it.rawValue },
    )
}

@Serializable
enum class NovelGhostwriteJobStatus {
    @SerialName("pending")
    Pending,

    @SerialName("running")
    Running,

    @SerialName("paused")
    Paused,

    @SerialName("completed")
    Completed,

    @SerialName("failed")
    Failed,

    @SerialName("cancelled")
    Cancelled,
}

@Serializable
enum class NovelGhostwriteJobPhase {
    @SerialName("awaitingPlan")
    AwaitingPlan,

    @SerialName("planning")
    Planning,

    @SerialName("planPrepared")
    PlanPrepared,

    @SerialName("generationPrepared")
    GenerationPrepared,

    @SerialName("generating")
    Generating,

    @SerialName("candidateReady")
    CandidateReady,

    @SerialName("validating")
    Validating,

    @SerialName("correctionReady")
    CorrectionReady,

    @SerialName("collectPrepared")
    CollectPrepared,

    @SerialName("collectedNeedsSync")
    CollectedNeedsSync,

    @SerialName("syncing")
    Syncing,

    @SerialName("clearPlanPrepared")
    ClearPlanPrepared,

    @SerialName("chapterCommitPrepared")
    ChapterCommitPrepared,

    @SerialName("chapterCommitted")
    ChapterCommitted,
}

@Serializable
data class NovelGhostwriteCorrectionPacketV1(
    val reasonCode: String,
    val summary: String,
    val missingMustHappen: List<String> = emptyList(),
    val forbiddenViolations: List<String> = emptyList(),
    val repetitionBeats: List<String> = emptyList(),
    val continuityNotes: List<String> = emptyList(),
    val sourceCandidateID: NovelCandidateId? = null,
    val planDigest: String,
    val fingerprint: String,
) {
    companion object {
        const val MAX_REASON_CODE_CHARACTERS = 80
        const val MAX_SUMMARY_CHARACTERS = 400
        const val MAX_ITEM_CHARACTERS = 120
        const val MAX_MISSING_MUST_HAPPEN = 6
        const val MAX_FORBIDDEN_VIOLATIONS = 6
        const val MAX_REPETITION_BEATS = 4
        const val MAX_CONTINUITY_NOTES = 4

        fun bounded(
            reasonCode: String,
            summary: String,
            missingMustHappen: List<String> = emptyList(),
            forbiddenViolations: List<String> = emptyList(),
            repetitionBeats: List<String> = emptyList(),
            continuityNotes: List<String> = emptyList(),
            sourceCandidateID: NovelCandidateId? = null,
            planDigest: String,
        ): NovelGhostwriteCorrectionPacketV1 {
            val reason = reasonCode.trim().take(MAX_REASON_CODE_CHARACTERS)
            require(reason.isNotEmpty()) { "Correction reason code is required." }
            val normalizedMissing = boundedItems(missingMustHappen, MAX_MISSING_MUST_HAPPEN)
            val normalizedForbidden = boundedItems(
                forbiddenViolations,
                MAX_FORBIDDEN_VIOLATIONS,
            )
            val normalizedRepetition = boundedItems(repetitionBeats, MAX_REPETITION_BEATS)
            val normalizedContinuity = boundedItems(continuityNotes, MAX_CONTINUITY_NOTES)
            val normalizedDigest = planDigest.trim().lowercase()
            val fingerprintPayload = listOf(
                reason,
                normalizedDigest,
                normalizedMissing.joinToString("\n"),
                normalizedForbidden.joinToString("\n"),
                normalizedRepetition.joinToString("\n"),
                normalizedContinuity.joinToString("\n"),
            ).joinToString("\n---\n")
            return NovelGhostwriteCorrectionPacketV1(
                reasonCode = reason,
                summary = summary.trim().take(MAX_SUMMARY_CHARACTERS),
                missingMustHappen = normalizedMissing,
                forbiddenViolations = normalizedForbidden,
                repetitionBeats = normalizedRepetition,
                continuityNotes = normalizedContinuity,
                sourceCandidateID = sourceCandidateID,
                planDigest = normalizedDigest,
                fingerprint = sha256HexOfUtf8(fingerprintPayload),
            )
        }

        private fun boundedItems(raw: List<String>, maximumCount: Int): List<String> {
            val seen = mutableSetOf<String>()
            return buildList {
                for (item in raw) {
                    val clipped = item.trim().take(MAX_ITEM_CHARACTERS)
                    if (clipped.isEmpty() || !seen.add(clipped.lowercase())) continue
                    add(clipped)
                    if (size >= maximumCount) break
                }
            }
        }
    }
}

@Serializable
data class NovelGhostwritePendingSyncIdentityV1(
    val syncOperationID: NovelOperationId,
    val checkpointID: NovelCheckpointId,
    val stateSnapshotID: NovelStateSnapshotId,
    val expectedProjectRevision: Long,
    val expectedCheckpointID: NovelCheckpointId,
    val expectedHeadRevision: Long,
    val expectedStateSnapshotID: NovelStateSnapshotId,
    val expectedConfigRevision: Long,
    val synchronizedProjectRevision: Long? = null,
    val synchronizedHeadRevision: Long? = null,
    val synchronizedConfigRevision: Long? = null,
)

@Serializable
data class NovelGhostwritePendingPlanUpsertV1(
    val upsertOperationID: NovelOperationId,
    val expectedProjectRevision: Long,
    val expectedConfigRevision: Long,
    val outlinePlacement: String,
    val goalAndConflict: String,
    val mustHappen: List<String>,
    val mustNotHappen: List<String>,
    val endingHook: String,
    val visibleFacts: List<String>,
    val upsertedProjectRevision: Long? = null,
    val upsertedConfigRevision: Long? = null,
)

@Serializable
data class NovelGhostwritePendingCollectIdentityV1(
    val chapterID: NovelChapterId,
    val chapterVersionID: NovelChapterVersionId,
    val collectOperationID: NovelOperationId,
    val checkpointID: NovelCheckpointId,
    val stateSnapshotID: NovelStateSnapshotId,
    val expectedProjectRevision: Long,
    val expectedHeadRevision: Long,
    val expectedConfigRevision: Long,
    val collectedProjectRevision: Long? = null,
    val collectedHeadRevision: Long? = null,
    val collectedConfigRevision: Long? = null,
)

@Serializable
data class NovelGhostwritePendingPlanClearV1(
    val clearOperationID: NovelOperationId,
    val expectedProjectRevision: Long,
    val expectedConfigRevision: Long,
    val clearedProjectRevision: Long? = null,
    val clearedConfigRevision: Long? = null,
)

@Serializable
data class NovelGhostwriteChapterCursorV1(
    val chapterIndex: Int,
    val baseProjectRevision: Long = 0,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val baseStateSnapshotID: NovelStateSnapshotId,
    val baseConfigRevision: Long,
    val planID: NovelChapterPlanId? = null,
    val planDigest: String? = null,
    val pendingPlanUpsert: NovelGhostwritePendingPlanUpsertV1? = null,
    val runID: NovelRunId? = null,
    val candidateID: NovelCandidateId? = null,
    val candidateContentSHA256: String? = null,
    val pendingCollectIdentity: NovelGhostwritePendingCollectIdentityV1? = null,
    val attemptNumber: Int = 0,
    val infraRetryCount: Int = 0,
    val pendingSyncIdentity: NovelGhostwritePendingSyncIdentityV1? = null,
    val pendingPlanClear: NovelGhostwritePendingPlanClearV1? = null,
    val correctionPacket: NovelGhostwriteCorrectionPacketV1? = null,
    val lastFailureFingerprint: String? = null,
    val sameFailureCount: Int = 0,
)

@Serializable
data class NovelGhostwriteChapterReceiptV1(
    val chapterIndex: Int,
    val baseProjectRevision: Long,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val baseStateSnapshotID: NovelStateSnapshotId,
    val baseConfigRevision: Long,
    val planID: NovelChapterPlanId,
    val planDigest: String,
    val planUpsertOperationID: NovelOperationId? = null,
    val planUpsertedProjectRevision: Long? = null,
    val planUpsertedConfigRevision: Long? = null,
    val runID: NovelRunId,
    val candidateID: NovelCandidateId,
    val candidateContentSHA256: String,
    val chapterID: NovelChapterId,
    val chapterVersionID: NovelChapterVersionId,
    val collectOperationID: NovelOperationId,
    val collectedCheckpointID: NovelCheckpointId,
    val collectedStateSnapshotID: NovelStateSnapshotId,
    val collectExpectedProjectRevision: Long,
    val collectedProjectRevision: Long,
    val collectedHeadRevision: Long,
    val collectedConfigRevision: Long,
    val syncOperationID: NovelOperationId,
    val synchronizedCheckpointID: NovelCheckpointId,
    val synchronizedProjectRevision: Long,
    val synchronizedHeadRevision: Long,
    val synchronizedStateSnapshotID: NovelStateSnapshotId,
    val synchronizedConfigRevision: Long,
    val clearPlanOperationID: NovelOperationId,
    val clearedProjectRevision: Long,
    val clearedConfigRevision: Long,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val completedAt: Instant,
)

@Serializable
data class NovelGhostwriteJobV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: NovelGhostwriteJobId,
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    val targetChapterCount: Int,
    val status: NovelGhostwriteJobStatus = NovelGhostwriteJobStatus.Pending,
    val phase: NovelGhostwriteJobPhase = NovelGhostwriteJobPhase.AwaitingPlan,
    val ledgerRevision: Long = 0,
    val executionEpoch: Long = 0,
    val leaseOwnerWorkID: String? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val leaseUntil: Instant? = null,
    val completedChapterCount: Int = 0,
    val chapterReceipts: List<NovelGhostwriteChapterReceiptV1> = emptyList(),
    val currentCursor: NovelGhostwriteChapterCursorV1,
    val statusReasonCode: String? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
) {
    val isTerminal: Boolean
        get() = status in setOf(
            NovelGhostwriteJobStatus.Completed,
            NovelGhostwriteJobStatus.Failed,
            NovelGhostwriteJobStatus.Cancelled,
        )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MIN_TARGET_CHAPTER_COUNT = 1
        const val MAX_TARGET_CHAPTER_COUNT = 50
        const val MAX_QUALITY_ATTEMPTS_PER_CHAPTER = 3
        const val MAX_INFRA_RETRIES_PER_PHASE = 3
        const val SAME_FAILURE_LIMIT = 2
        const val MAX_LEASE_OWNER_CHARACTERS = 128
        const val MAX_STATUS_REASON_CODE_CHARACTERS = 120
    }
}
