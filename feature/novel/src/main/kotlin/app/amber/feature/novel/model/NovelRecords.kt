package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelBareUuidSerializer
import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class NovelQuickStartSeed(
    val genre: String,
    val coreIdea: String,
)

@Serializable
data class NovelChapterPlanRecord(
    val id: NovelChapterPlanId,
    val branchID: NovelBranchId,
    val status: NovelChapterPlanStatus,
    val outlinePlacement: String,
    val goalAndConflict: String,
    val mustHappen: List<String>,
    val mustNotHappen: List<String>,
    val endingHook: String,
    val visibleFacts: List<String>,
    val contentDigest: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val confirmedAt: Instant? = null,
) {
    val isConfirmed: Boolean
        get() = status == NovelChapterPlanStatus.Confirmed

    fun canonicalDigestPayload(): String = listOf(
        outlinePlacement.trim(),
        goalAndConflict.trim(),
        normalizedLines(mustHappen).joinToString("\n"),
        normalizedLines(mustNotHappen).joinToString("\n"),
        endingHook.trim(),
        normalizedLines(visibleFacts).joinToString("\n"),
    ).joinToString("\n---\n")

    fun injectionText(): String = buildList {
        add("Status: ${status.name.lowercase()}")
        add("Digest: $contentDigest")
        add("Placement: $outlinePlacement")
        add("Goal and conflict:\n$goalAndConflict")
        if (mustHappen.isNotEmpty()) add("Must happen:\n" + mustHappen.joinToString("\n") { "- $it" })
        if (mustNotHappen.isNotEmpty()) add("Must not happen:\n" + mustNotHappen.joinToString("\n") { "- $it" })
        if (endingHook.isNotBlank()) add("Ending hook:\n$endingHook")
        if (visibleFacts.isNotEmpty()) add("POV-visible facts:\n" + visibleFacts.joinToString("\n") { "- $it" })
    }.joinToString("\n\n")

    companion object {
        fun digest(forCanonicalPayload: String): String = sha256HexOfUtf8(forCanonicalPayload)

        fun normalizedLines(lines: List<String>): List<String> = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

@Serializable
data class NovelUpcomingArcRecord(
    val branchID: NovelBranchId,
    val beats: List<String>,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
) {
    fun injectionText(): String = beats.joinToString("\n") { "- $it" }

    companion object {
        const val MAX_BEATS = 8
        const val MAX_BEAT_CHARACTER_COUNT = 160

        fun normalizedBeats(raw: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return buildList {
                for (item in raw) {
                    val clipped = item.trim().take(MAX_BEAT_CHARACTER_COUNT)
                    if (clipped.isEmpty() || !seen.add(clipped.lowercase())) continue
                    add(clipped)
                    if (size >= MAX_BEATS) break
                }
            }
        }
    }
}

@Serializable
data class NovelProjectRecord(
    val id: NovelProjectId,
    val name: String,
    val creationMode: NovelProjectCreationMode,
    val quickStartSeed: NovelQuickStartSeed? = null,
    /** Suggested character aliases (P5-01 schema v2); preserved round-trip. */
    val aliases: List<String> = emptyList(),
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
    val revision: Long,
    val configRevision: Long,
    val mainBranchID: NovelBranchId,
    val modelPolicy: NovelProjectModelPolicy,
    /**
     * Optional model used for state-delta / manual-sync extraction.
     * Null means fall back to [modelPolicy] (writing model) so single-model projects
     * keep working without a second setting. Distinct from iOS which falls back to Global
     * when null (iOS has a separate preferences store).
     */
    val stateSyncModelPolicy: NovelProjectModelPolicy? = null,
    val lastGenerationGranularity: NovelGenerationGranularity,
    val polishPreference: String,
    val collaborationMode: NovelCollaborationMode = NovelCollaborationMode.Cocreation,
    val pauseGhostwriteOnBlockingContinuity: Boolean = true,
    val reviewModelPolicy: NovelProjectModelPolicy? = null,
) {
    fun effectiveStateSyncModelPolicy(): NovelProjectModelPolicy =
        stateSyncModelPolicy ?: modelPolicy

    fun effectiveReviewModelPolicy(): NovelProjectModelPolicy =
        reviewModelPolicy ?: NovelProjectModelPolicy.Global
}

@Serializable
data class NovelMaterialRecord(
    val id: NovelMaterialId,
    val kind: NovelMaterialKind,
    val currentRevisionID: NovelMaterialRevisionId,
    val revisionIDs: List<NovelMaterialRevisionId>,
    val isDeleted: Boolean = false,
)

@Serializable
data class NovelMaterialRevisionRecord(
    val id: NovelMaterialRevisionId,
    val materialID: NovelMaterialId,
    val revision: Long,
    val title: String,
    val content: String,
    val tags: List<String>,
    /** Alternative names matched by the Smart material matcher (P5-02). */
    val aliases: List<String> = emptyList(),
    val injectionMode: NovelInjectionMode,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val operationID: NovelOperationId,
)

@Serializable
data class NovelForkOrigin(
    val parentBranchID: NovelBranchId,
    val checkpointID: NovelCheckpointId,
)

@Serializable
data class NovelChapterSelection(
    val chapterID: NovelChapterId,
    val versionID: NovelChapterVersionId,
)

@Serializable
data class NovelBranchRecord(
    val id: NovelBranchId,
    val name: String,
    val sessionID: NovelSessionId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
    val forkOrigin: NovelForkOrigin? = null,
    val headCheckpointID: NovelCheckpointId,
    val currentStateSnapshotID: NovelStateSnapshotId,
    val headRevision: Long,
    val workingRevision: Long,
    val syncStatus: NovelBranchSyncStatus,
    val lifecycle: NovelBranchLifecycle,
    val overrideRevisionIDs: List<NovelMaterialRevisionId> = emptyList(),
    val workingChapterSelections: List<NovelChapterSelection> = emptyList(),
    val activeRunID: NovelRunId? = null,
)

@Serializable
data class NovelSessionMessageRecord(
    val id: NovelMessageId,
    val sequence: Long,
    val role: NovelSessionRole,
    val mode: NovelSessionMode,
    val kind: NovelSessionMessageKind,
    val content: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val runID: NovelRunId? = null,
    val candidateID: NovelCandidateId? = null,
    /** iOS Ask User prompt/answer attached to this message, when present. */
    val interaction: NovelSessionMessageInteraction? = null,
)

@Serializable
data class NovelDiscussionArchiveRecord(
    val id: NovelMessageId,
    val checkpointID: NovelCheckpointId,
    val throughSequence: Long,
    val messageCount: Int,
    val chapterID: NovelChapterId? = null,
    val summary: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

/** Session interaction state (P5-01 schema v2), e.g. character identity clarification. */
@Serializable
data class NovelSessionInteraction(
    val kind: NovelSessionInteractionKind,
    val resolved: Boolean,
)

/** Contextual character mention on a session (P5-01 schema v2). */
@Serializable
data class NovelContextualCharacterMention(
    val mention: String,
    val resolvedCharacterID: NovelMaterialId? = null,
)

@Serializable
data class NovelSessionRecord(
    val id: NovelSessionId,
    val branchID: NovelBranchId,
    val revision: Long,
    val messages: List<NovelSessionMessageRecord> = emptyList(),
    /** Messages with sequence ≤ cursor are considered archived out of injection window. */
    val archiveCursor: NovelSessionCursor? = null,
    val discussionArchives: List<NovelDiscussionArchiveRecord> = emptyList(),
    /** Pending interaction with the user (P5-01 schema v2). */
    val interaction: NovelSessionInteraction? = null,
    /** In-context character mention (P5-01 schema v2). */
    val contextualCharacter: NovelContextualCharacterMention? = null,
)

@Serializable
data class NovelCandidateRecord(
    val id: NovelCandidateId,
    val kind: NovelCandidateKind,
    val branchID: NovelBranchId,
    val sessionID: NovelSessionId,
    val sourceMessageID: NovelMessageId,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val status: NovelCandidateStatus,
    val content: String,
    val sourceChapterVersionID: NovelChapterVersionId? = null,
    val clonedFromCandidateID: NovelCandidateId? = null,
    val collectedCheckpointID: NovelCheckpointId? = null,
    val chapterPlanDigest: String? = null,
    val ghostwritePlanID: NovelChapterPlanId? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelChapterRecord(
    val id: NovelChapterId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    /** When set, chapter is discarded from the active manuscript (iOS-aligned). */
    @Serializable(with = NovelSwiftDateSerializer::class)
    val discardedAt: Instant? = null,
)

@Serializable
data class NovelChapterVersionRecord(
    val id: NovelChapterVersionId,
    val chapterID: NovelChapterId,
    val kind: NovelChapterVersionKind,
    val title: String,
    val content: String,
    @Serializable(with = NovelBareUuidSerializer::class)
    val factCompatibilityID: UUID,
    val sourceChapterVersionID: NovelChapterVersionId? = null,
    val sourceCandidateID: NovelCandidateId? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val operationID: NovelOperationId,
)

@Serializable
data class NovelStoryEventRecord(
    val id: NovelEventId,
    val sequence: Long,
    val kind: String,
    val summary: String,
    val entityReferences: List<String> = emptyList(),
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelCharacterIdentityClarificationRecord(
    val mention: String,
    val clarification: String,
    val operationID: NovelOperationId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelStateSnapshotRecord(
    val id: NovelStateSnapshotId,
    val eventIDs: List<NovelEventId> = emptyList(),
    val summary: String,
    val branchOutline: String,
    val unresolvedEntityNames: List<String> = emptyList(),
    val characterIdentityClarifications: List<NovelCharacterIdentityClarificationRecord> = emptyList(),
    val settingProposalIDs: List<NovelProposalId> = emptyList(),
    val recentWrittenHighlights: List<String> = emptyList(),
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
) {
    fun injectionHighlightsText(): String = recentWrittenHighlights.joinToString("\n") { "- $it" }

    companion object {
        const val MAX_RECENT_WRITTEN_HIGHLIGHTS = 24
        const val MAX_HIGHLIGHT_CHARACTER_COUNT = 160

        fun mergedHighlights(prior: List<String>, newEventSummaries: List<String>): List<String> =
            normalizedHighlights(prior + newEventSummaries)

        fun normalizedHighlights(raw: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            val normalized = buildList {
                for (item in raw) {
                    val clipped = item.trim().take(MAX_HIGHLIGHT_CHARACTER_COUNT)
                    if (clipped.isEmpty() || !seen.add(clipped.lowercase())) continue
                    add(clipped)
                }
            }
            return normalized.takeLast(MAX_RECENT_WRITTEN_HIGHLIGHTS)
        }
    }
}

@Serializable
data class NovelBranchCheckpointRecord(
    val id: NovelCheckpointId,
    val kind: NovelCheckpointKind,
    val createdOnBranchID: NovelBranchId,
    val parentCheckpointID: NovelCheckpointId? = null,
    val chapterSelections: List<NovelChapterSelection> = emptyList(),
    val stateSnapshotID: NovelStateSnapshotId,
    val sessionCursor: NovelSessionCursor,
    val branchOverrideRevisionIDs: List<NovelMaterialRevisionId> = emptyList(),
    val sourceCandidateID: NovelCandidateId? = null,
    val baseHeadRevision: Long,
    val operationID: NovelOperationId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelFailure(
    val code: String,
    val message: String,
    val isRetryable: Boolean,
)

@Serializable
data class NovelFactReceiptLink(
    val pendingID: NovelPendingOperationId,
    val ownerOperationID: NovelOperationId,
    val attemptOperationID: NovelOperationId,
    val attemptPayloadSHA256: String,
    val kind: NovelFactReceiptKind,
    val chunkIndex: Int? = null,
)

@Serializable
data class NovelGenerationReceiptRecord(
    val id: NovelReceiptId,
    val runID: NovelRunId,
    val providerID: String,
    val ownerProviderID: String,
    val modelID: String,
    val wireModelID: String,
    val promptVersion: String,
    val injectionReceiptID: NovelReceiptId,
    val parameters: Map<String, String> = emptyMap(),
    val requestSHA256: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val factTransaction: NovelFactReceiptLink? = null,
)

@Serializable
data class NovelInjectionReceiptSectionRecord(
    val kind: NovelInjectionSectionKind,
    val label: String,
    val reason: NovelInjectionSelectionReason,
    val estimatedTokens: Int,
    val contentSHA256: String,
)

@Serializable
data class NovelMaterialInjectionDecision(
    val materialID: NovelMaterialId,
    val revisionID: NovelMaterialRevisionId,
    val included: Boolean,
    val reason: NovelInjectionSelectionReason,
    val relevanceScore: Int,
    val estimatedTokens: Int,
    val contentSHA256: String,
    /** Smart matcher hit detail (which fields matched), recorded into the receipt (P5-02). */
    val matchReasons: List<String> = emptyList(),
)

@Serializable
data class NovelInjectionReceiptRecord(
    val id: NovelReceiptId,
    val runID: NovelRunId,
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    val promptVersion: String,
    val providerID: String,
    val ownerProviderID: String,
    val modelID: String,
    val wireModelID: String,
    val parameters: Map<String, String> = emptyMap(),
    val sections: List<NovelInjectionReceiptSectionRecord> = emptyList(),
    val materialDecisions: List<NovelMaterialInjectionDecision> = emptyList(),
    val forceIncludeMaterialIDs: List<NovelMaterialId> = emptyList(),
    val forceExcludeMaterialIDs: List<NovelMaterialId> = emptyList(),
    val requestedInputBudgetTokens: Int,
    val maxEstimatedInputTokens: Int,
    val estimatedInputTokens: Int,
    val canonicalInputSHA256: String,
    val factTransaction: NovelFactReceiptLink? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelActiveRunRecord(
    val id: NovelRunId,
    val operationID: NovelOperationId,
    val requestPayloadSHA256: String,
    val branchID: NovelBranchId,
    val sessionID: NovelSessionId,
    val kind: NovelRunKind,
    val mode: NovelSessionMode,
    val granularity: NovelGenerationGranularity? = null,
    val userMessageID: NovelMessageId,
    val messageID: NovelMessageId,
    val candidateID: NovelCandidateId? = null,
    val sourceChapterVersionID: NovelChapterVersionId? = null,
    /** iOS character-proposal runs retain the unresolved source mention. */
    val contextualCharacterMention: String? = null,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val status: NovelRunStatus,
    val partialContent: String = "",
    val receiptID: NovelReceiptId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val startedAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val terminalAt: Instant? = null,
    val interruptionReason: NovelRunInterruptionReason? = null,
    val terminalFailure: NovelFailure? = null,
    val chapterPlanDigest: String? = null,
    val ghostwritePlanID: NovelChapterPlanId? = null,
)

@Serializable
data class NovelFactAttemptRecord(
    val pendingID: NovelPendingOperationId,
    val ownerOperationID: NovelOperationId,
    val attemptOperationID: NovelOperationId,
    val attemptPayloadSHA256: String,
    val branchID: NovelBranchId,
    val kind: NovelFactReceiptKind,
    val firstChunkIndex: Int? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelPendingOperationRecord(
    val id: NovelPendingOperationId,
    val kind: NovelPendingOperationKind,
    val status: NovelPendingOperationStatus,
    val branchID: NovelBranchId,
    val operationID: NovelOperationId,
    val payloadSHA256: String,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val baseWorkingRevision: Long = 0,
    val candidateID: NovelCandidateId? = null,
    val collectionTarget: NovelCollectionTarget? = null,
    val selectedText: String = "",
    val proposedChapterVersion: NovelChapterVersionRecord? = null,
    val proposedCheckpointID: NovelCheckpointId? = null,
    val proposedStateSnapshotID: NovelStateSnapshotId? = null,
    val rebuildBaseCheckpointID: NovelCheckpointId? = null,
    val sessionCursor: NovelSessionCursor? = null,
    /**
     * Opaque iOS ManualSync progress blob. Preserved round-trip so Android decode+encode
     * does not drop iOS-only continuation state (ignoreUnknownKeys alone is not enough).
     */
    val manualSyncProgress: kotlinx.serialization.json.JsonObject? = null,
    val createdAt: @Serializable(with = NovelSwiftDateSerializer::class) Instant,
    val lastError: String? = null,
)

@Serializable
data class NovelSettingProposalRecord(
    val id: NovelProposalId,
    val branchID: NovelBranchId,
    val title: String,
    val content: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val isResolved: Boolean,
    val origin: NovelSettingProposalOrigin? = null,
    /** iOS QuickStart character aliases retained until the proposal is materialized. */
    val suggestedCharacterAliases: List<String>? = null,
)

@Serializable
data class NovelPendingPolishTransactionRecord(
    val id: NovelPendingOperationId,
    val operationID: NovelOperationId,
    val payloadSHA256: String,
    val branchID: NovelBranchId,
    val candidateID: NovelCandidateId,
    val sourceChapterVersionID: NovelChapterVersionId,
    val proposedChapterVersionID: NovelChapterVersionId,
    val checkpointID: NovelCheckpointId,
    val baseCheckpointID: NovelCheckpointId,
    val baseHeadRevision: Long,
    val baseWorkingRevision: Long,
    val sessionCursor: NovelSessionCursor,
    val sourceContentSHA256: String,
    val candidateContentSHA256: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    val status: NovelPolishTransactionStatus,
    val attemptCount: Int,
    val lastFailure: NovelFailure? = null,
    val lastFailureAttemptIndex: Int? = null,
)

@Serializable
data class NovelPolishAttemptRecord(
    val transactionID: NovelPendingOperationId,
    val attemptIndex: Int,
    val runID: NovelRunId,
    val injectionReceiptID: NovelReceiptId,
    val generationReceiptID: NovelReceiptId,
    val sourceContentSHA256: String,
    val candidateContentSHA256: String,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelPolishDifferenceV1(
    val id: String,
    val category: String,
    val summary: String,
    val sourceEvidence: String,
    val candidateEvidence: String,
)

@Serializable
data class NovelPolishDriftV1(
    val schemaVersion: Int,
    val compatible: Boolean,
    val differences: List<NovelPolishDifferenceV1> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class NovelPolishAssessmentRecord(
    val transactionID: NovelPendingOperationId,
    val attemptIndex: Int,
    val runID: NovelRunId,
    val result: NovelPolishDriftV1? = null,
    val failure: NovelFailure? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelAppliedOperationRecord(
    val operationID: NovelOperationId,
    val kind: NovelOperationKind,
    val payloadSHA256: String,
    val outcome: NovelOutcome,
    val appliedProjectRevision: Long,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val appliedAt: Instant,
)

@Serializable
enum class NovelRecoveryTerminalKind {
    Completed,
    Failed,
}

@Serializable
data class NovelRecoveryChapterRevisionProposalV1(
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    val chapterID: NovelChapterId,
    val chapterVersionID: NovelChapterVersionId,
    val chapterOrdinal: Int,
    val chapterTitle: String,
    val startParagraph: Int,
    val endParagraph: Int,
    val oldText: String,
    val newText: String,
    val newContent: String,
    val reason: String? = null,
    val baseProjectRevision: Long,
    val baseBranchHeadRevision: Long,
    val baseWorkingRevision: Long,
)

@Serializable
data class NovelRecoveryProjectToolRevertApprovalV1(
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    val proposal: NovelManuscriptRevertProposal,
)

@Serializable
data class NovelRecoveryTerminalV1(
    val kind: NovelRecoveryTerminalKind,
    val content: String? = null,
    val failure: NovelFailure? = null,
    val chapterRevisionApprovals: List<NovelRecoveryChapterRevisionProposalV1> = emptyList(),
    val projectToolRevertApprovals: List<NovelRecoveryProjectToolRevertApprovalV1> = emptyList(),
)

@Serializable
data class NovelRecoverySidecarV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectID: NovelProjectId,
    val runID: NovelRunId,
    val branchID: NovelBranchId,
    val sessionID: NovelSessionId,
    val messageID: NovelMessageId,
    val baseProjectRevision: Long,
    val sequence: Long,
    val partialContent: String,
    val partialSHA256: String,
    /** Optional durable terminal intent; absent on ordinary streaming recovery snapshots. */
    val terminal: NovelRecoveryTerminalV1? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
