package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelBareUuidSerializer
import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class NovelQuickStartSeed(
    val genre: String,
    val coreIdea: String,
)

@Serializable
data class NovelProjectRecord(
    val id: NovelProjectId,
    val name: String,
    val creationMode: NovelProjectCreationMode,
    val quickStartSeed: NovelQuickStartSeed? = null,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
    val revision: Long,
    val configRevision: Long,
    val mainBranchID: NovelBranchId,
    val modelPolicy: NovelProjectModelPolicy,
    val lastGenerationGranularity: NovelGenerationGranularity,
    val polishPreference: String,
)

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
)

@Serializable
data class NovelSessionRecord(
    val id: NovelSessionId,
    val branchID: NovelBranchId,
    val revision: Long,
    val messages: List<NovelSessionMessageRecord> = emptyList(),
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
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

@Serializable
data class NovelChapterRecord(
    val id: NovelChapterId,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
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
data class NovelStateSnapshotRecord(
    val id: NovelStateSnapshotId,
    val eventIDs: List<NovelEventId> = emptyList(),
    val summary: String,
    val branchOutline: String,
    val unresolvedEntityNames: List<String> = emptyList(),
    val settingProposalIDs: List<NovelProposalId> = emptyList(),
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
)

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
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
