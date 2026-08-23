package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.decodeSwiftAssociatedCase
import app.amber.feature.novel.serialization.swiftAssociatedObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Durable operation outcomes stored on [NovelAppliedOperationRecord].
 * Wire format matches Swift associated enums (single-key objects).
 */
@Serializable(with = NovelOutcome.Serializer::class)
sealed class NovelOutcome {
    data class ProjectCreated(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
    ) : NovelOutcome()

    data class ProjectRenamed(
        val projectID: NovelProjectId,
        val revision: Long,
    ) : NovelOutcome()

    data class MaterialRevised(
        val projectID: NovelProjectId,
        val materialID: NovelMaterialId,
        val revisionID: NovelMaterialRevisionId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class MaterialDeleted(
        val projectID: NovelProjectId,
        val materialID: NovelMaterialId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class ModelPolicyChanged(
        val projectID: NovelProjectId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class SettingProposalAccepted(
        val projectID: NovelProjectId,
        val proposalID: NovelProposalId,
        val materialID: NovelMaterialId,
        val revisionID: NovelMaterialRevisionId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class SettingProposalRejected(
        val projectID: NovelProjectId,
        val proposalID: NovelProposalId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class BranchMaterialOverrideChanged(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val materialID: NovelMaterialId,
        val revisionID: NovelMaterialRevisionId?,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class MainBranchChanged(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val revision: Long,
    ) : NovelOutcome()

    data class PolishPreferenceChanged(
        val projectID: NovelProjectId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class ProjectAliasesChanged(
        val projectID: NovelProjectId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class CharacterIdentityResolved(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val sessionID: NovelSessionId,
        val mention: String,
        val resolvedCharacterID: NovelMaterialId?,
        val projectRevision: Long,
    ) : NovelOutcome()

    /** iOS wire outcome for a checkpointed character-identity clarification. */
    data class CharacterIdentityClarified(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val mention: String,
        val checkpointID: NovelCheckpointId,
        val stateSnapshotID: NovelStateSnapshotId,
        val revision: Long,
    ) : NovelOutcome()

    data class CollaborationModeChanged(
        val projectID: NovelProjectId,
        val mode: NovelCollaborationMode,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class PauseGhostwriteOnBlockingContinuityChanged(
        val projectID: NovelProjectId,
        val enabled: Boolean,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class ChapterPlanUpserted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val planID: NovelChapterPlanId,
        val status: NovelChapterPlanStatus,
        val contentDigest: String,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class ChapterPlanCleared(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class UpcomingArcUpserted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val beatCount: Int,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class UpcomingArcCleared(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class BranchForked(
        val projectID: NovelProjectId,
        val sourceBranchID: NovelBranchId,
        val branchID: NovelBranchId,
        val checkpointID: NovelCheckpointId,
        val revision: Long,
    ) : NovelOutcome()

    data class BranchRenamed(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val revision: Long,
    ) : NovelOutcome()

    data class BranchDeleted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val revision: Long,
    ) : NovelOutcome()

    data class BranchHeadMoved(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val fromCheckpointID: NovelCheckpointId,
        val toCheckpointID: NovelCheckpointId,
        val headRevision: Long,
        val revision: Long,
    ) : NovelOutcome()

    data class CandidateCloned(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val sourceCandidateID: NovelCandidateId,
        val candidateID: NovelCandidateId,
        val revision: Long,
    ) : NovelOutcome()

    data class PolishCandidateAdopted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val candidateID: NovelCandidateId,
        val checkpointID: NovelCheckpointId,
        val chapterVersionID: NovelChapterVersionId,
        val revision: Long,
    ) : NovelOutcome()

    data class PolishCandidateRejected(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val candidateID: NovelCandidateId,
        val transactionID: NovelPendingOperationId,
        val revision: Long,
    ) : NovelOutcome()

    data class PolishTransactionAbandoned(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val candidateID: NovelCandidateId,
        val transactionID: NovelPendingOperationId,
        val revision: Long,
    ) : NovelOutcome()

    data class ChapterDiscardStateChanged(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val chapterID: NovelChapterId,
        val isDiscarded: Boolean,
        val revision: Long,
    ) : NovelOutcome()

    data class ChapterRemovedFromManuscript(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val chapterID: NovelChapterId,
        val workingRevision: Long,
        val revision: Long,
    ) : NovelOutcome()

    data class ChapterVersionRestored(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val checkpointID: NovelCheckpointId,
        val chapterVersionID: NovelChapterVersionId,
        val revision: Long,
    ) : NovelOutcome()

    data class DiscussionArchived(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val archiveID: NovelMessageId,
        val checkpointID: NovelCheckpointId,
        val decisionRevisionIDs: List<NovelMaterialRevisionId>,
        val projectRevision: Long,
        val configRevision: Long,
    ) : NovelOutcome()

    data class RunStarted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val runID: NovelRunId,
        val receiptID: NovelReceiptId,
        val revision: Long,
    ) : NovelOutcome()

    data class RunInterrupted(
        val projectID: NovelProjectId,
        val runID: NovelRunId,
        val reason: NovelRunInterruptionReason,
        val revision: Long,
    ) : NovelOutcome()

    /** Transient acknowledgement for RetryTerminal; never written to the operation ledger. */
    data class RunTerminalRetried(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val runID: NovelRunId,
        val revision: Long,
    ) : NovelOutcome()

    data class CandidateCollected(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val candidateID: NovelCandidateId,
        val checkpointID: NovelCheckpointId,
        val chapterVersionID: NovelChapterVersionId,
        val revision: Long,
    ) : NovelOutcome()

    data class ManualEditSaved(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val chapterVersionID: NovelChapterVersionId,
        val workingRevision: Long,
        val revision: Long,
    ) : NovelOutcome()

    data class ManualSyncCommitted(
        val projectID: NovelProjectId,
        val branchID: NovelBranchId,
        val checkpointID: NovelCheckpointId,
        val revision: Long,
    ) : NovelOutcome()

    data class ProjectImported(
        val sourceProjectID: NovelProjectId,
        val projectID: NovelProjectId,
        val disposition: NovelProjectImportDisposition,
        val interruptedRunCount: Int,
        val revision: Long,
    ) : NovelOutcome()

    data class PreviousProjectRestored(
        val projectID: NovelProjectId,
        val revision: Long,
    ) : NovelOutcome()

    data class ProjectDeleted(
        val projectID: NovelProjectId,
    ) : NovelOutcome()

    object Serializer : KSerializer<NovelOutcome> {
        override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NovelOutcome")

        override fun serialize(encoder: Encoder, value: NovelOutcome) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                is ProjectCreated -> swiftAssociatedObject(
                    "projectCreated",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                    },
                )
                is CandidateCollected -> swiftAssociatedObject(
                    "candidateCollected",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("candidateID", json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.candidateID))
                        put("checkpointID", json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID))
                        put(
                            "chapterVersionID",
                            json.json.encodeToJsonElement(NovelChapterVersionId.Serializer, value.chapterVersionID),
                        )
                        put("revision", value.revision)
                    },
                )
                is ProjectRenamed -> swiftAssociatedObject(
                    "projectRenamed",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("revision", value.revision)
                    },
                )
                is ProjectDeleted -> swiftAssociatedObject(
                    "projectDeleted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                    },
                )
                is RunStarted -> swiftAssociatedObject(
                    "runStarted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("runID", json.json.encodeToJsonElement(NovelRunId.Serializer, value.runID))
                        put("receiptID", json.json.encodeToJsonElement(NovelReceiptId.Serializer, value.receiptID))
                        put("revision", value.revision)
                    },
                )
                is RunInterrupted -> swiftAssociatedObject(
                    "runInterrupted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("runID", json.json.encodeToJsonElement(NovelRunId.Serializer, value.runID))
                        put("reason", value.reason.name.replaceFirstChar { it.lowercase() }.let {
                            // Map enum names carefully for camelCase wire values.
                            when (value.reason) {
                                NovelRunInterruptionReason.User -> "user"
                                NovelRunInterruptionReason.Background -> "background"
                                NovelRunInterruptionReason.RouteExit -> "routeExit"
                                NovelRunInterruptionReason.Expiration -> "expiration"
                                NovelRunInterruptionReason.Recovery -> "recovery"
                            }
                        })
                        put("revision", value.revision)
                    },
                )
                is RunTerminalRetried -> swiftAssociatedObject(
                    "runTerminalRetried",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("runID", json.json.encodeToJsonElement(NovelRunId.Serializer, value.runID))
                        put("revision", value.revision)
                    },
                )
                is BranchForked -> swiftAssociatedObject(
                    "branchForked",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put(
                            "sourceBranchID",
                            json.json.encodeToJsonElement(NovelBranchId.Serializer, value.sourceBranchID),
                        )
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put("revision", value.revision)
                    },
                )
                is ManualEditSaved -> swiftAssociatedObject(
                    "manualEditSaved",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "chapterVersionID",
                            json.json.encodeToJsonElement(NovelChapterVersionId.Serializer, value.chapterVersionID),
                        )
                        put("workingRevision", value.workingRevision)
                        put("revision", value.revision)
                    },
                )
                is ManualSyncCommitted -> swiftAssociatedObject(
                    "manualSyncCommitted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put("revision", value.revision)
                    },
                )
                is PreviousProjectRestored -> swiftAssociatedObject(
                    "previousProjectRestored",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("revision", value.revision)
                    },
                )
                is ProjectImported -> swiftAssociatedObject(
                    "projectImported",
                    buildJsonObject {
                        put(
                            "sourceProjectID",
                            json.json.encodeToJsonElement(NovelProjectId.Serializer, value.sourceProjectID),
                        )
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put(
                            "disposition",
                            when (value.disposition) {
                                NovelProjectImportDisposition.Created -> "created"
                                NovelProjectImportDisposition.Replaced -> "replaced"
                                NovelProjectImportDisposition.KeptBoth -> "keptBoth"
                            },
                        )
                        put("interruptedRunCount", value.interruptedRunCount)
                        put("revision", value.revision)
                    },
                )
                // Remaining cases serialize with labeled fields for completeness.
                else -> serializeGeneric(json, value)
            }
            json.encodeJsonElement(element)
        }

        private fun serializeGeneric(json: JsonEncoder, value: NovelOutcome) =
            // Fallback: encode remaining cases that are not yet common in fixtures.
            when (value) {
                is MaterialRevised -> swiftAssociatedObject(
                    "materialRevised",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("materialID", json.json.encodeToJsonElement(NovelMaterialId.Serializer, value.materialID))
                        put(
                            "revisionID",
                            json.json.encodeToJsonElement(NovelMaterialRevisionId.Serializer, value.revisionID),
                        )
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is MaterialDeleted -> swiftAssociatedObject(
                    "materialDeleted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("materialID", json.json.encodeToJsonElement(NovelMaterialId.Serializer, value.materialID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is ModelPolicyChanged -> swiftAssociatedObject(
                    "modelPolicyChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is SettingProposalAccepted -> swiftAssociatedObject(
                    "settingProposalAccepted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("proposalID", json.json.encodeToJsonElement(NovelProposalId.Serializer, value.proposalID))
                        put("materialID", json.json.encodeToJsonElement(NovelMaterialId.Serializer, value.materialID))
                        put(
                            "revisionID",
                            json.json.encodeToJsonElement(NovelMaterialRevisionId.Serializer, value.revisionID),
                        )
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is SettingProposalRejected -> swiftAssociatedObject(
                    "settingProposalRejected",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("proposalID", json.json.encodeToJsonElement(NovelProposalId.Serializer, value.proposalID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is BranchMaterialOverrideChanged -> swiftAssociatedObject(
                    "branchMaterialOverrideChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("materialID", json.json.encodeToJsonElement(NovelMaterialId.Serializer, value.materialID))
                        if (value.revisionID != null) {
                            put(
                                "revisionID",
                                json.json.encodeToJsonElement(
                                    NovelMaterialRevisionId.Serializer,
                                    value.revisionID,
                                ),
                            )
                        }
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is MainBranchChanged -> swiftAssociatedObject(
                    "mainBranchChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("revision", value.revision)
                    },
                )
                is PolishPreferenceChanged -> swiftAssociatedObject(
                    "polishPreferenceChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is ProjectAliasesChanged -> swiftAssociatedObject(
                    "projectAliasesChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is CharacterIdentityResolved -> swiftAssociatedObject(
                    "characterIdentityResolved",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("sessionID", json.json.encodeToJsonElement(NovelSessionId.Serializer, value.sessionID))
                        put("mention", value.mention)
                        if (value.resolvedCharacterID != null) {
                            put(
                                "resolvedCharacterID",
                                json.json.encodeToJsonElement(NovelMaterialId.Serializer, value.resolvedCharacterID),
                            )
                        }
                        put("projectRevision", value.projectRevision)
                    },
                )
                is CharacterIdentityClarified -> swiftAssociatedObject(
                    "characterIdentityClarified",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("mention", value.mention)
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put(
                            "stateSnapshotID",
                            json.json.encodeToJsonElement(NovelStateSnapshotId.Serializer, value.stateSnapshotID),
                        )
                        put("revision", value.revision)
                    },
                )
                is CollaborationModeChanged -> swiftAssociatedObject(
                    "collaborationModeChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put(
                            "mode",
                            when (value.mode) {
                                NovelCollaborationMode.Cocreation -> "cocreation"
                                NovelCollaborationMode.Ghostwrite -> "ghostwrite"
                            },
                        )
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is PauseGhostwriteOnBlockingContinuityChanged -> swiftAssociatedObject(
                    "pauseGhostwriteOnBlockingContinuityChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("enabled", value.enabled)
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is ChapterPlanUpserted -> swiftAssociatedObject(
                    "chapterPlanUpserted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("planID", json.json.encodeToJsonElement(NovelChapterPlanId.Serializer, value.planID))
                        put(
                            "status",
                            when (value.status) {
                                NovelChapterPlanStatus.Draft -> "draft"
                                NovelChapterPlanStatus.Confirmed -> "confirmed"
                            },
                        )
                        put("contentDigest", value.contentDigest)
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is ChapterPlanCleared -> swiftAssociatedObject(
                    "chapterPlanCleared",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is UpcomingArcUpserted -> swiftAssociatedObject(
                    "upcomingArcUpserted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("beatCount", value.beatCount)
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is UpcomingArcCleared -> swiftAssociatedObject(
                    "upcomingArcCleared",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                is BranchRenamed -> swiftAssociatedObject(
                    "branchRenamed",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("revision", value.revision)
                    },
                )
                is BranchDeleted -> swiftAssociatedObject(
                    "branchDeleted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("revision", value.revision)
                    },
                )
                is BranchHeadMoved -> swiftAssociatedObject(
                    "branchHeadMoved",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "fromCheckpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.fromCheckpointID),
                        )
                        put(
                            "toCheckpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.toCheckpointID),
                        )
                        put("headRevision", value.headRevision)
                        put("revision", value.revision)
                    },
                )
                is CandidateCloned -> swiftAssociatedObject(
                    "candidateCloned",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "sourceCandidateID",
                            json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.sourceCandidateID),
                        )
                        put(
                            "candidateID",
                            json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.candidateID),
                        )
                        put("revision", value.revision)
                    },
                )
                is PolishCandidateAdopted -> swiftAssociatedObject(
                    "polishCandidateAdopted",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "candidateID",
                            json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.candidateID),
                        )
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put(
                            "chapterVersionID",
                            json.json.encodeToJsonElement(NovelChapterVersionId.Serializer, value.chapterVersionID),
                        )
                        put("revision", value.revision)
                    },
                )
                is PolishCandidateRejected -> swiftAssociatedObject(
                    "polishCandidateRejected",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "candidateID",
                            json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.candidateID),
                        )
                        put(
                            "transactionID",
                            json.json.encodeToJsonElement(NovelPendingOperationId.Serializer, value.transactionID),
                        )
                        put("revision", value.revision)
                    },
                )
                is PolishTransactionAbandoned -> swiftAssociatedObject(
                    "polishTransactionAbandoned",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "candidateID",
                            json.json.encodeToJsonElement(NovelCandidateId.Serializer, value.candidateID),
                        )
                        put(
                            "transactionID",
                            json.json.encodeToJsonElement(NovelPendingOperationId.Serializer, value.transactionID),
                        )
                        put("revision", value.revision)
                    },
                )
                is ChapterVersionRestored -> swiftAssociatedObject(
                    "chapterVersionRestored",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put(
                            "chapterVersionID",
                            json.json.encodeToJsonElement(NovelChapterVersionId.Serializer, value.chapterVersionID),
                        )
                        put("revision", value.revision)
                    },
                )
                is ChapterDiscardStateChanged -> swiftAssociatedObject(
                    "chapterDiscardStateChanged",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("chapterID", json.json.encodeToJsonElement(NovelChapterId.Serializer, value.chapterID))
                        put("isDiscarded", value.isDiscarded)
                        put("revision", value.revision)
                    },
                )
                is ChapterRemovedFromManuscript -> swiftAssociatedObject(
                    "chapterRemovedFromManuscript",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("chapterID", json.json.encodeToJsonElement(NovelChapterId.Serializer, value.chapterID))
                        put("workingRevision", value.workingRevision)
                        put("revision", value.revision)
                    },
                )
                is DiscussionArchived -> swiftAssociatedObject(
                    "discussionArchived",
                    buildJsonObject {
                        put("projectID", json.json.encodeToJsonElement(NovelProjectId.Serializer, value.projectID))
                        put("branchID", json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID))
                        put("archiveID", json.json.encodeToJsonElement(NovelMessageId.Serializer, value.archiveID))
                        put(
                            "checkpointID",
                            json.json.encodeToJsonElement(NovelCheckpointId.Serializer, value.checkpointID),
                        )
                        put(
                            "decisionRevisionIDs",
                            kotlinx.serialization.json.JsonArray(
                                value.decisionRevisionIDs.map {
                                    json.json.encodeToJsonElement(NovelMaterialRevisionId.Serializer, it)
                                },
                            ),
                        )
                        put("projectRevision", value.projectRevision)
                        put("configRevision", value.configRevision)
                    },
                )
                else -> error("Unhandled NovelOutcome serialization: ${value::class.simpleName}")
            }

        override fun deserialize(decoder: Decoder): NovelOutcome {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            fun projectId(key: String = "projectID") =
                json.json.decodeFromJsonElement(NovelProjectId.Serializer, associated.getValue(key))

            fun branchId(key: String = "branchID") =
                json.json.decodeFromJsonElement(NovelBranchId.Serializer, associated.getValue(key))

            return when (caseName) {
                "projectCreated" -> ProjectCreated(projectId(), branchId())
                "projectRenamed" -> ProjectRenamed(projectId(), associated.getValue("revision").jsonPrimitive.long)
                "candidateCollected" -> CandidateCollected(
                    projectID = projectId(),
                    branchID = branchId(),
                    candidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("candidateID"),
                    ),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    chapterVersionID = json.json.decodeFromJsonElement(
                        NovelChapterVersionId.Serializer,
                        associated.getValue("chapterVersionID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "runStarted" -> RunStarted(
                    projectID = projectId(),
                    branchID = branchId(),
                    runID = json.json.decodeFromJsonElement(NovelRunId.Serializer, associated.getValue("runID")),
                    receiptID = json.json.decodeFromJsonElement(
                        NovelReceiptId.Serializer,
                        associated.getValue("receiptID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "runInterrupted" -> RunInterrupted(
                    projectID = projectId(),
                    runID = json.json.decodeFromJsonElement(NovelRunId.Serializer, associated.getValue("runID")),
                    reason = when (associated.getValue("reason").jsonPrimitive.content) {
                        "user" -> NovelRunInterruptionReason.User
                        "background" -> NovelRunInterruptionReason.Background
                        "routeExit" -> NovelRunInterruptionReason.RouteExit
                        "expiration" -> NovelRunInterruptionReason.Expiration
                        "recovery" -> NovelRunInterruptionReason.Recovery
                        else -> error("Unknown interruption reason")
                    },
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "runTerminalRetried" -> RunTerminalRetried(
                    projectID = projectId(),
                    branchID = branchId(),
                    runID = json.json.decodeFromJsonElement(
                        NovelRunId.Serializer,
                        associated.getValue("runID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "branchForked" -> BranchForked(
                    projectID = projectId(),
                    sourceBranchID = branchId("sourceBranchID"),
                    branchID = branchId(),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "manualEditSaved" -> ManualEditSaved(
                    projectID = projectId(),
                    branchID = branchId(),
                    chapterVersionID = json.json.decodeFromJsonElement(
                        NovelChapterVersionId.Serializer,
                        associated.getValue("chapterVersionID"),
                    ),
                    workingRevision = associated.getValue("workingRevision").jsonPrimitive.long,
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "manualSyncCommitted" -> ManualSyncCommitted(
                    projectID = projectId(),
                    branchID = branchId(),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "projectImported" -> ProjectImported(
                    sourceProjectID = projectId("sourceProjectID"),
                    projectID = projectId(),
                    disposition = when (associated.getValue("disposition").jsonPrimitive.content) {
                        "created" -> NovelProjectImportDisposition.Created
                        "replaced" -> NovelProjectImportDisposition.Replaced
                        "keptBoth" -> NovelProjectImportDisposition.KeptBoth
                        else -> error("Unknown disposition")
                    },
                    interruptedRunCount = associated.getValue("interruptedRunCount").jsonPrimitive.content.toInt(),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "previousProjectRestored" -> PreviousProjectRestored(
                    projectId(),
                    associated.getValue("revision").jsonPrimitive.long,
                )
                "projectDeleted" -> ProjectDeleted(projectId())
                "materialRevised" -> MaterialRevised(
                    projectID = projectId(),
                    materialID = json.json.decodeFromJsonElement(
                        NovelMaterialId.Serializer,
                        associated.getValue("materialID"),
                    ),
                    revisionID = json.json.decodeFromJsonElement(
                        NovelMaterialRevisionId.Serializer,
                        associated.getValue("revisionID"),
                    ),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "materialDeleted" -> MaterialDeleted(
                    projectID = projectId(),
                    materialID = json.json.decodeFromJsonElement(
                        NovelMaterialId.Serializer,
                        associated.getValue("materialID"),
                    ),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "modelPolicyChanged" -> ModelPolicyChanged(
                    projectID = projectId(),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "settingProposalAccepted" -> SettingProposalAccepted(
                    projectID = projectId(),
                    proposalID = json.json.decodeFromJsonElement(
                        NovelProposalId.Serializer,
                        associated.getValue("proposalID"),
                    ),
                    materialID = json.json.decodeFromJsonElement(
                        NovelMaterialId.Serializer,
                        associated.getValue("materialID"),
                    ),
                    revisionID = json.json.decodeFromJsonElement(
                        NovelMaterialRevisionId.Serializer,
                        associated.getValue("revisionID"),
                    ),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "settingProposalRejected" -> SettingProposalRejected(
                    projectID = projectId(),
                    proposalID = json.json.decodeFromJsonElement(
                        NovelProposalId.Serializer,
                        associated.getValue("proposalID"),
                    ),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "branchMaterialOverrideChanged" -> BranchMaterialOverrideChanged(
                    projectID = projectId(),
                    branchID = branchId(),
                    materialID = json.json.decodeFromJsonElement(
                        NovelMaterialId.Serializer,
                        associated.getValue("materialID"),
                    ),
                    revisionID = associated["revisionID"]?.let {
                        json.json.decodeFromJsonElement(NovelMaterialRevisionId.Serializer, it)
                    },
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "mainBranchChanged" -> MainBranchChanged(
                    projectId(),
                    branchId(),
                    associated.getValue("revision").jsonPrimitive.long,
                )
                "polishPreferenceChanged" -> PolishPreferenceChanged(
                    projectId(),
                    associated.getValue("projectRevision").jsonPrimitive.long,
                    associated.getValue("configRevision").jsonPrimitive.long,
                )
                "projectAliasesChanged" -> ProjectAliasesChanged(
                    projectId(),
                    associated.getValue("projectRevision").jsonPrimitive.long,
                    associated.getValue("configRevision").jsonPrimitive.long,
                )
                "characterIdentityResolved" -> CharacterIdentityResolved(
                    projectID = projectId(),
                    branchID = branchId(),
                    sessionID = json.json.decodeFromJsonElement(
                        NovelSessionId.Serializer,
                        associated.getValue("sessionID"),
                    ),
                    mention = associated.getValue("mention").jsonPrimitive.content,
                    resolvedCharacterID = associated["resolvedCharacterID"]?.let {
                        json.json.decodeFromJsonElement(NovelMaterialId.Serializer, it)
                    },
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                )
                "characterIdentityClarified" -> CharacterIdentityClarified(
                    projectID = projectId(),
                    branchID = branchId(),
                    mention = associated.getValue("mention").jsonPrimitive.content,
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    stateSnapshotID = json.json.decodeFromJsonElement(
                        NovelStateSnapshotId.Serializer,
                        associated.getValue("stateSnapshotID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "collaborationModeChanged" -> CollaborationModeChanged(
                    projectID = projectId(),
                    mode = when (associated.getValue("mode").jsonPrimitive.content) {
                        "cocreation" -> NovelCollaborationMode.Cocreation
                        "ghostwrite" -> NovelCollaborationMode.Ghostwrite
                        else -> error("Unknown collaboration mode")
                    },
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "pauseGhostwriteOnBlockingContinuityChanged" ->
                    PauseGhostwriteOnBlockingContinuityChanged(
                        projectID = projectId(),
                        enabled = associated.getValue("enabled").jsonPrimitive.content.toBooleanStrict(),
                        projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                        configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                    )
                "chapterPlanUpserted" -> ChapterPlanUpserted(
                    projectID = projectId(),
                    branchID = branchId(),
                    planID = json.json.decodeFromJsonElement(
                        NovelChapterPlanId.Serializer,
                        associated.getValue("planID"),
                    ),
                    status = when (associated.getValue("status").jsonPrimitive.content) {
                        "draft" -> NovelChapterPlanStatus.Draft
                        "confirmed" -> NovelChapterPlanStatus.Confirmed
                        else -> error("Unknown chapter plan status")
                    },
                    contentDigest = associated.getValue("contentDigest").jsonPrimitive.content,
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "chapterPlanCleared" -> ChapterPlanCleared(
                    projectID = projectId(),
                    branchID = branchId(),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "upcomingArcUpserted" -> UpcomingArcUpserted(
                    projectID = projectId(),
                    branchID = branchId(),
                    beatCount = associated.getValue("beatCount").jsonPrimitive.content.toInt(),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "upcomingArcCleared" -> UpcomingArcCleared(
                    projectID = projectId(),
                    branchID = branchId(),
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                "branchRenamed" -> BranchRenamed(
                    projectId(),
                    branchId(),
                    associated.getValue("revision").jsonPrimitive.long,
                )
                "branchDeleted" -> BranchDeleted(
                    projectId(),
                    branchId(),
                    associated.getValue("revision").jsonPrimitive.long,
                )
                "branchHeadMoved" -> BranchHeadMoved(
                    projectID = projectId(),
                    branchID = branchId(),
                    fromCheckpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("fromCheckpointID"),
                    ),
                    toCheckpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("toCheckpointID"),
                    ),
                    headRevision = associated.getValue("headRevision").jsonPrimitive.long,
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "candidateCloned" -> CandidateCloned(
                    projectID = projectId(),
                    branchID = branchId(),
                    sourceCandidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("sourceCandidateID"),
                    ),
                    candidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("candidateID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "polishCandidateAdopted" -> PolishCandidateAdopted(
                    projectID = projectId(),
                    branchID = branchId(),
                    candidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("candidateID"),
                    ),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    chapterVersionID = json.json.decodeFromJsonElement(
                        NovelChapterVersionId.Serializer,
                        associated.getValue("chapterVersionID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "polishCandidateRejected" -> PolishCandidateRejected(
                    projectID = projectId(),
                    branchID = branchId(),
                    candidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("candidateID"),
                    ),
                    transactionID = json.json.decodeFromJsonElement(
                        NovelPendingOperationId.Serializer,
                        associated.getValue("transactionID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "polishTransactionAbandoned" -> PolishTransactionAbandoned(
                    projectID = projectId(),
                    branchID = branchId(),
                    candidateID = json.json.decodeFromJsonElement(
                        NovelCandidateId.Serializer,
                        associated.getValue("candidateID"),
                    ),
                    transactionID = json.json.decodeFromJsonElement(
                        NovelPendingOperationId.Serializer,
                        associated.getValue("transactionID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "chapterVersionRestored" -> ChapterVersionRestored(
                    projectID = projectId(),
                    branchID = branchId(),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    chapterVersionID = json.json.decodeFromJsonElement(
                        NovelChapterVersionId.Serializer,
                        associated.getValue("chapterVersionID"),
                    ),
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "chapterDiscardStateChanged" -> ChapterDiscardStateChanged(
                    projectID = projectId(),
                    branchID = branchId(),
                    chapterID = json.json.decodeFromJsonElement(
                        NovelChapterId.Serializer,
                        associated.getValue("chapterID"),
                    ),
                    isDiscarded = associated.getValue("isDiscarded").jsonPrimitive.boolean,
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "chapterRemovedFromManuscript" -> ChapterRemovedFromManuscript(
                    projectID = projectId(),
                    branchID = branchId(),
                    chapterID = json.json.decodeFromJsonElement(
                        NovelChapterId.Serializer,
                        associated.getValue("chapterID"),
                    ),
                    workingRevision = associated.getValue("workingRevision").jsonPrimitive.long,
                    revision = associated.getValue("revision").jsonPrimitive.long,
                )
                "discussionArchived" -> DiscussionArchived(
                    projectID = projectId(),
                    branchID = branchId(),
                    archiveID = json.json.decodeFromJsonElement(
                        NovelMessageId.Serializer,
                        associated.getValue("archiveID"),
                    ),
                    checkpointID = json.json.decodeFromJsonElement(
                        NovelCheckpointId.Serializer,
                        associated.getValue("checkpointID"),
                    ),
                    decisionRevisionIDs = associated.getValue("decisionRevisionIDs")
                        .let { el ->
                            (el as? kotlinx.serialization.json.JsonArray)?.map {
                                json.json.decodeFromJsonElement(NovelMaterialRevisionId.Serializer, it)
                            }.orEmpty()
                        },
                    projectRevision = associated.getValue("projectRevision").jsonPrimitive.long,
                    configRevision = associated.getValue("configRevision").jsonPrimitive.long,
                )
                else -> throw IllegalArgumentException("Unknown NovelOutcome case: $caseName")
            }
        }
    }
}
