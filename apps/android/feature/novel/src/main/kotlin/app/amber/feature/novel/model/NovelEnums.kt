package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelBareUuidSerializer
import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import app.amber.feature.novel.serialization.decodeSwiftAssociatedCase
import app.amber.feature.novel.serialization.swiftAssociatedObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID

/**
 * Project creation mode (P5-01 schema v2).
 *
 * Unknown raw values (e.g. iOS `storyboardV2`) decode as [Unknown] and are
 * re-encoded verbatim, so an unknown enum never rejects the whole package.
 */
@Serializable(with = NovelProjectCreationMode.Serializer::class)
sealed class NovelProjectCreationMode {
    data object Blank : NovelProjectCreationMode()

    data object QuickStart : NovelProjectCreationMode()

    /** Unrecognized wire value, preserved verbatim for round-trip fidelity. */
    data class Unknown(val rawValue: String) : NovelProjectCreationMode()

    val wireValue: String
        get() = when (this) {
            Blank -> "blank"
            QuickStart -> "quickStart"
            is Unknown -> rawValue
        }

    object Serializer : KSerializer<NovelProjectCreationMode> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("NovelProjectCreationMode", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: NovelProjectCreationMode) {
            encoder.encodeString(value.wireValue)
        }

        override fun deserialize(decoder: Decoder): NovelProjectCreationMode =
            when (val raw = decoder.decodeString()) {
                "blank" -> Blank
                "quickStart" -> QuickStart
                else -> Unknown(raw)
            }
    }
}

/**
 * Session interaction kind (P5-01 schema v2), e.g. `characterIdentityClarification`.
 * Unknown kinds decode as [Unknown] and re-encode verbatim.
 */
@Serializable(with = NovelSessionInteractionKind.Serializer::class)
sealed class NovelSessionInteractionKind {
    data object CharacterIdentityClarification : NovelSessionInteractionKind()

    data object ContextualCharacter : NovelSessionInteractionKind()

    data class Unknown(val rawValue: String) : NovelSessionInteractionKind()

    val wireValue: String
        get() = when (this) {
            CharacterIdentityClarification -> "characterIdentityClarification"
            ContextualCharacter -> "contextualCharacter"
            is Unknown -> rawValue
        }

    object Serializer : KSerializer<NovelSessionInteractionKind> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("NovelSessionInteractionKind", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: NovelSessionInteractionKind) {
            encoder.encodeString(value.wireValue)
        }

        override fun deserialize(decoder: Decoder): NovelSessionInteractionKind =
            when (val raw = decoder.decodeString()) {
                "characterIdentityClarification" -> CharacterIdentityClarification
                "contextualCharacter" -> ContextualCharacter
                else -> Unknown(raw)
            }
    }
}

@Serializable
enum class NovelCollaborationMode {
    @SerialName("cocreation")
    Cocreation,

    @SerialName("ghostwrite")
    Ghostwrite,
}

@Serializable
enum class NovelChapterPlanStatus {
    @SerialName("draft")
    Draft,

    @SerialName("confirmed")
    Confirmed,
}

@Serializable
enum class NovelGenerationGranularity {
    @SerialName("continuation")
    Continuation,

    @SerialName("wholeChapter")
    WholeChapter,
}

@Serializable
enum class NovelCollectionSource {
    @SerialName("user")
    User,

    @SerialName("systemAutoCollect")
    SystemAutoCollect;

    val wireValue: String
        get() = when (this) {
            User -> "user"
            SystemAutoCollect -> "systemAutoCollect"
        }
}

@Serializable
enum class NovelInjectionMode {
    @SerialName("always")
    Always,

    @SerialName("smart")
    Smart,

    @SerialName("off")
    Off,
}

@Serializable
enum class NovelBranchSyncStatus {
    @SerialName("synchronized")
    Synchronized,

    @SerialName("needsSync")
    NeedsSync,
}

@Serializable
enum class NovelBranchLifecycle {
    @SerialName("active")
    Active,

    @SerialName("deleted")
    Deleted,
}

@Serializable
enum class NovelCheckpointKind {
    @SerialName("initial")
    Initial,

    @SerialName("collection")
    Collection,

    @SerialName("manualSync")
    ManualSync,

    @SerialName("polish")
    Polish,

    @SerialName("restore")
    Restore,

    @SerialName("discussionArchive")
    DiscussionArchive,

    @SerialName("identityClarification")
    IdentityClarification,
}

@Serializable
enum class NovelChapterVersionKind {
    @SerialName("collected")
    Collected,

    @SerialName("manualEdit")
    ManualEdit,

    @SerialName("polish")
    Polish,

    @SerialName("restore")
    Restore,
}

@Serializable
enum class NovelSessionRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,

    @SerialName("system")
    System,
}

@Serializable
enum class NovelSessionMode {
    @SerialName("writeProse")
    WriteProse,

    @SerialName("discussPlan")
    DiscussPlan,
}

@Serializable
enum class NovelSessionMessageKind {
    @SerialName("userInput")
    UserInput,

    @SerialName("discussion")
    Discussion,

    @SerialName("proseCandidate")
    ProseCandidate,

    @SerialName("polishCandidate")
    PolishCandidate,

    @SerialName("interruptedDraft")
    InterruptedDraft,

    @SerialName("error")
    Error,
}

@Serializable
enum class NovelCandidateKind {
    @SerialName("prose")
    Prose,

    @SerialName("polish")
    Polish,
}

@Serializable
enum class NovelCandidateStatus {
    @SerialName("available")
    Available,

    @SerialName("collected")
    Collected,

    @SerialName("adopted")
    Adopted,

    @SerialName("interrupted")
    Interrupted,

    @SerialName("superseded")
    Superseded,

    @SerialName("inheritedReadOnly")
    InheritedReadOnly,
}

@Serializable
enum class NovelRunStatus {
    @SerialName("running")
    Running,

    @SerialName("completed")
    Completed,

    @SerialName("interrupted")
    Interrupted,

    @SerialName("failed")
    Failed,

    /** Run replaced by a newer run (P5-01 schema v2). Preserved round-trip. */
    @SerialName("superseded")
    Superseded,
}

@Serializable
enum class NovelRunInterruptionReason {
    @SerialName("user")
    User,

    @SerialName("background")
    Background,

    @SerialName("routeExit")
    RouteExit,

    @SerialName("expiration")
    Expiration,

    @SerialName("recovery")
    Recovery,
}

@Serializable
enum class NovelPendingOperationKind {
    @SerialName("collection")
    Collection,

    @SerialName("manualSync")
    ManualSync,
}

@Serializable
enum class NovelPendingOperationStatus {
    @SerialName("pending")
    Pending,

    @SerialName("retryable")
    Retryable,
}

@Serializable
enum class NovelFactReceiptKind {
    @SerialName("stateDelta")
    StateDelta,

    @SerialName("manualRebuild")
    ManualRebuild,
}

@Serializable
enum class NovelRunKind {
    @SerialName("quickStart")
    QuickStart,

    @SerialName("characterProposal")
    CharacterProposal,

    @SerialName("discussion")
    Discussion,

    @SerialName("prose")
    Prose,

    @SerialName("polish")
    Polish,

    /** Whole-chapter rewrite; produces a prose candidate for replaceChapter collect. */
    @SerialName("regenerate")
    Regenerate,
}

@Serializable
enum class NovelOperationKind {
    @SerialName("createProject")
    CreateProject,

    @SerialName("renameProject")
    RenameProject,

    @SerialName("reviseMaterial")
    ReviseMaterial,

    @SerialName("deleteMaterial")
    DeleteMaterial,

    @SerialName("setModelPolicy")
    SetModelPolicy,

    @SerialName("resolveSettingProposal")
    ResolveSettingProposal,

    @SerialName("setBranchMaterialOverride")
    SetBranchMaterialOverride,

    @SerialName("setMainBranch")
    SetMainBranch,

    @SerialName("setPolishPreference")
    SetPolishPreference,

    @SerialName("setCollaborationMode")
    SetCollaborationMode,

    @SerialName("setPauseGhostwriteOnBlockingContinuity")
    SetPauseGhostwriteOnBlockingContinuity,

    @SerialName("upsertChapterPlan")
    UpsertChapterPlan,

    @SerialName("clearChapterPlan")
    ClearChapterPlan,

    @SerialName("upsertUpcomingArc")
    UpsertUpcomingArc,

    @SerialName("clearUpcomingArc")
    ClearUpcomingArc,

    @SerialName("forkBranch")
    ForkBranch,

    @SerialName("renameBranch")
    RenameBranch,

    @SerialName("deleteBranch")
    DeleteBranch,

    @SerialName("undoBranchHead")
    UndoBranchHead,

    @SerialName("cloneCandidate")
    CloneCandidate,

    @SerialName("adoptPolishCandidate")
    AdoptPolishCandidate,

    @SerialName("abandonPolishTransaction")
    AbandonPolishTransaction,

    @SerialName("restoreChapterVersion")
    RestoreChapterVersion,

    @SerialName("discardChapter")
    DiscardChapter,

    @SerialName("restoreChapter")
    RestoreChapter,

    @SerialName("deleteChapterFromManuscript")
    DeleteChapterFromManuscript,

    @SerialName("archiveDiscussion")
    ArchiveDiscussion,

    @SerialName("clarifyCharacterIdentity")
    ClarifyCharacterIdentity,

    @SerialName("startRun")
    StartRun,

    @SerialName("cancelRun")
    CancelRun,

    @SerialName("collectCandidate")
    CollectCandidate,

    @SerialName("saveManualEdit")
    SaveManualEdit,

    @SerialName("syncManualEdits")
    SyncManualEdits,

    @SerialName("retryPending")
    RetryPending,

    @SerialName("importProject")
    ImportProject,

    @SerialName("restorePreviousProject")
    RestorePreviousProject,

    @SerialName("deleteProject")
    DeleteProject,
}

@Serializable
enum class NovelProjectImportDisposition {
    @SerialName("created")
    Created,

    @SerialName("replaced")
    Replaced,

    @SerialName("keptBoth")
    KeptBoth,
}

@Serializable
enum class NovelPolishTransactionStatus {
    @SerialName("pending")
    Pending,

    @SerialName("retryable")
    Retryable,

    @SerialName("incompatible")
    Incompatible,

    @SerialName("completed")
    Completed,

    @SerialName("blocked")
    Blocked,

    @SerialName("abandoned")
    Abandoned,
}

@Serializable
enum class NovelInjectionSelectionReason {
    @SerialName("requiredPrompt")
    RequiredPrompt,

    @SerialName("requiredPolishPreference")
    RequiredPolishPreference,

    @SerialName("confirmedChapterPlan")
    ConfirmedChapterPlan,

    @SerialName("recentWrittenHighlights")
    RecentWrittenHighlights,

    @SerialName("upcomingArc")
    UpcomingArc,

    @SerialName("requiredUserInput")
    RequiredUserInput,

    @SerialName("requiredCurrentState")
    RequiredCurrentState,

    @SerialName("requiredQuickStartSeed")
    RequiredQuickStartSeed,

    @SerialName("currentChapterTail")
    CurrentChapterTail,

    @SerialName("previousChapterTail")
    PreviousChapterTail,

    @SerialName("fullSourceChapter")
    FullSourceChapter,

    @SerialName("archivedDiscussion")
    ArchivedDiscussion,

    @SerialName("recentSession")
    RecentSession,

    @SerialName("branchEventHistory")
    BranchEventHistory,

    @SerialName("branchOverride")
    BranchOverride,

    @SerialName("always")
    Always,

    @SerialName("forceIncluded")
    ForceIncluded,

    @SerialName("smartMatch")
    SmartMatch,

    @SerialName("forceExcluded")
    ForceExcluded,

    @SerialName("disabled")
    Disabled,

    @SerialName("noSmartMatch")
    NoSmartMatch,

    @SerialName("budgetTrimmed")
    BudgetTrimmed,
}

@Serializable(with = NovelProjectModelPolicy.Serializer::class)
sealed class NovelProjectModelPolicy {
    data object Global : NovelProjectModelPolicy()

    data class Fixed(
        val providerID: String,
        val modelID: String,
    ) : NovelProjectModelPolicy()

    object Serializer : KSerializer<NovelProjectModelPolicy> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelProjectModelPolicy")

        override fun serialize(encoder: Encoder, value: NovelProjectModelPolicy) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                Global -> swiftAssociatedObject("global")
                is Fixed -> swiftAssociatedObject(
                    "fixed",
                    buildJsonObject {
                        put("modelID", value.modelID)
                        put("providerID", value.providerID)
                    },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelProjectModelPolicy {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "global" -> Global
                "fixed" -> Fixed(
                    providerID = associated.getValue("providerID").jsonPrimitive.content,
                    modelID = associated.getValue("modelID").jsonPrimitive.content,
                )
                else -> throw IllegalArgumentException("Unknown model policy case: $caseName")
            }
        }
    }
}

@Serializable(with = NovelMaterialKind.Serializer::class)
sealed class NovelMaterialKind {
    data object World : NovelMaterialKind()
    data object Character : NovelMaterialKind()
    /** Author-approved relationship planning; runtime facts remain in state snapshots. */
    data object Relationship : NovelMaterialKind()
    data object MasterOutline : NovelMaterialKind()
    data object WritingRequirements : NovelMaterialKind()
    data object DecisionLog : NovelMaterialKind()
    data class Custom(val value: String) : NovelMaterialKind()

    object Serializer : KSerializer<NovelMaterialKind> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelMaterialKind")

        override fun serialize(encoder: Encoder, value: NovelMaterialKind) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                World -> swiftAssociatedObject("world")
                Character -> swiftAssociatedObject("character")
                Relationship -> swiftAssociatedObject("relationship")
                MasterOutline -> swiftAssociatedObject("masterOutline")
                WritingRequirements -> swiftAssociatedObject("writingRequirements")
                DecisionLog -> swiftAssociatedObject("decisionLog")
                is Custom -> swiftAssociatedObject(
                    "custom",
                    buildJsonObject { put("_0", value.value) },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelMaterialKind {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "world" -> World
                "character" -> Character
                "relationship" -> Relationship
                "masterOutline" -> MasterOutline
                "writingRequirements" -> WritingRequirements
                "decisionLog" -> DecisionLog
                "custom" -> Custom(associated.getValue("_0").jsonPrimitive.content)
                else -> throw IllegalArgumentException("Unknown material kind: $caseName")
            }
        }
    }
}

@Serializable(with = NovelSessionCursor.Serializer::class)
sealed class NovelSessionCursor {
    data object Empty : NovelSessionCursor()
    data class Through(val sequence: Long) : NovelSessionCursor()

    object Serializer : KSerializer<NovelSessionCursor> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelSessionCursor")

        override fun serialize(encoder: Encoder, value: NovelSessionCursor) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                Empty -> swiftAssociatedObject("empty")
                is Through -> swiftAssociatedObject(
                    "through",
                    buildJsonObject { put("sequence", value.sequence) },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelSessionCursor {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "empty" -> Empty
                "through" -> Through(associated.getValue("sequence").jsonPrimitive.long)
                else -> throw IllegalArgumentException("Unknown session cursor: $caseName")
            }
        }
    }
}

@Serializable(with = NovelCollectionTarget.Serializer::class)
sealed class NovelCollectionTarget {
    data class AppendToChapter(val chapterID: NovelChapterId) : NovelCollectionTarget()
    data class CreateNextChapter(
        val chapterID: NovelChapterId,
        val title: String,
    ) : NovelCollectionTarget()

    /** Replace the working head of an existing chapter (regenerate collect default). */
    data class ReplaceChapter(val chapterID: NovelChapterId) : NovelCollectionTarget()

    object Serializer : KSerializer<NovelCollectionTarget> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelCollectionTarget")

        override fun serialize(encoder: Encoder, value: NovelCollectionTarget) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                is AppendToChapter -> swiftAssociatedObject(
                    "appendToChapter",
                    buildJsonObject {
                        put("_0", json.json.encodeToJsonElement(NovelChapterId.Serializer, value.chapterID))
                    },
                )
                is CreateNextChapter -> swiftAssociatedObject(
                    "createNextChapter",
                    buildJsonObject {
                        put("chapterID", json.json.encodeToJsonElement(NovelChapterId.Serializer, value.chapterID))
                        put("title", value.title)
                    },
                )
                is ReplaceChapter -> swiftAssociatedObject(
                    "replaceChapter",
                    buildJsonObject {
                        put("_0", json.json.encodeToJsonElement(NovelChapterId.Serializer, value.chapterID))
                    },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelCollectionTarget {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "appendToChapter" -> AppendToChapter(
                    json.json.decodeFromJsonElement(
                        NovelChapterId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "createNextChapter" -> CreateNextChapter(
                    chapterID = json.json.decodeFromJsonElement(
                        NovelChapterId.Serializer,
                        associated.getValue("chapterID"),
                    ),
                    title = associated.getValue("title").jsonPrimitive.content,
                )
                "replaceChapter" -> ReplaceChapter(
                    json.json.decodeFromJsonElement(
                        NovelChapterId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                else -> throw IllegalArgumentException("Unknown collection target: $caseName")
            }
        }
    }
}

@Serializable(with = NovelProjectImportPolicy.Serializer::class)
sealed class NovelProjectImportPolicy {
    data object Reject : NovelProjectImportPolicy()
    data class Replace(val expectedRevision: Long) : NovelProjectImportPolicy()
    data class KeepBoth(val destinationProjectID: NovelProjectId) : NovelProjectImportPolicy()

    object Serializer : KSerializer<NovelProjectImportPolicy> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelProjectImportPolicy")

        override fun serialize(encoder: Encoder, value: NovelProjectImportPolicy) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                Reject -> swiftAssociatedObject("reject")
                is Replace -> swiftAssociatedObject(
                    "replace",
                    buildJsonObject { put("expectedRevision", value.expectedRevision) },
                )
                is KeepBoth -> swiftAssociatedObject(
                    "keepBoth",
                    buildJsonObject {
                        put(
                            "destinationProjectID",
                            json.json.encodeToJsonElement(
                                NovelProjectId.Serializer,
                                value.destinationProjectID,
                            ),
                        )
                    },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelProjectImportPolicy {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "reject" -> Reject
                "replace" -> Replace(associated.getValue("expectedRevision").jsonPrimitive.long)
                "keepBoth" -> KeepBoth(
                    json.json.decodeFromJsonElement(
                        NovelProjectId.Serializer,
                        associated.getValue("destinationProjectID"),
                    ),
                )
                else -> throw IllegalArgumentException("Unknown import policy: $caseName")
            }
        }
    }
}

@Serializable(with = NovelSettingProposalOrigin.Serializer::class)
sealed class NovelSettingProposalOrigin {
    data object DerivedState : NovelSettingProposalOrigin()
    data class QuickStart(
        val runID: NovelRunId,
        val suggestedKind: NovelMaterialKind,
    ) : NovelSettingProposalOrigin()

    data class ContextualCharacter(
        val runID: NovelRunId,
        val sourceMention: String,
        val suggestedKind: NovelMaterialKind,
    ) : NovelSettingProposalOrigin()

    object Serializer : KSerializer<NovelSettingProposalOrigin> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelSettingProposalOrigin")

        override fun serialize(encoder: Encoder, value: NovelSettingProposalOrigin) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                DerivedState -> swiftAssociatedObject("derivedState")
                is QuickStart -> swiftAssociatedObject(
                    "quickStart",
                    buildJsonObject {
                        put("runID", json.json.encodeToJsonElement(NovelRunId.Serializer, value.runID))
                        put(
                            "suggestedKind",
                            json.json.encodeToJsonElement(NovelMaterialKind.Serializer, value.suggestedKind),
                        )
                    },
                )
                is ContextualCharacter -> swiftAssociatedObject(
                    "contextualCharacter",
                    buildJsonObject {
                        put("runID", json.json.encodeToJsonElement(NovelRunId.Serializer, value.runID))
                        put("sourceMention", value.sourceMention)
                        put(
                            "suggestedKind",
                            json.json.encodeToJsonElement(NovelMaterialKind.Serializer, value.suggestedKind),
                        )
                    },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelSettingProposalOrigin {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "derivedState" -> DerivedState
                "quickStart" -> QuickStart(
                    runID = json.json.decodeFromJsonElement(
                        NovelRunId.Serializer,
                        associated.getValue("runID"),
                    ),
                    suggestedKind = json.json.decodeFromJsonElement(
                        NovelMaterialKind.Serializer,
                        associated.getValue("suggestedKind"),
                    ),
                )
                "contextualCharacter" -> ContextualCharacter(
                    runID = json.json.decodeFromJsonElement(
                        NovelRunId.Serializer,
                        associated.getValue("runID"),
                    ),
                    sourceMention = associated.getValue("sourceMention").jsonPrimitive.content,
                    suggestedKind = json.json.decodeFromJsonElement(
                        NovelMaterialKind.Serializer,
                        associated.getValue("suggestedKind"),
                    ),
                )
                else -> throw IllegalArgumentException("Unknown proposal origin: $caseName")
            }
        }
    }
}

@Serializable(with = NovelInjectionSectionKind.Serializer::class)
sealed class NovelInjectionSectionKind {
    data object FixedPrompt : NovelInjectionSectionKind()
    data object PolishPreference : NovelInjectionSectionKind()
    data class ChapterPlan(val planID: NovelChapterPlanId) : NovelInjectionSectionKind()
    data class RecentWrittenHighlights(val snapshotID: NovelStateSnapshotId) : NovelInjectionSectionKind()
    data class UpcomingArc(val branchID: NovelBranchId) : NovelInjectionSectionKind()
    data class CurrentState(val snapshotID: NovelStateSnapshotId) : NovelInjectionSectionKind()
    data class PendingManualState(
        val pendingID: NovelPendingOperationId,
        val chunkIndex: Int,
    ) : NovelInjectionSectionKind()

    data object QuickStartSeed : NovelInjectionSectionKind()
    data class ChapterContext(val versionID: NovelChapterVersionId?) : NovelInjectionSectionKind()
    data class DiscussionArchive(
        val messageID: NovelMessageId,
        val throughSequence: Long,
    ) : NovelInjectionSectionKind()
    data class SessionMessage(val messageID: NovelMessageId) : NovelInjectionSectionKind()
    data class StoryEvent(val eventID: NovelEventId) : NovelInjectionSectionKind()
    data class Material(val revisionID: NovelMaterialRevisionId) : NovelInjectionSectionKind()
    data object UserInput : NovelInjectionSectionKind()

    object Serializer : KSerializer<NovelInjectionSectionKind> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelInjectionSectionKind")

        override fun serialize(encoder: Encoder, value: NovelInjectionSectionKind) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                FixedPrompt -> swiftAssociatedObject("fixedPrompt")
                PolishPreference -> swiftAssociatedObject("polishPreference")
                is ChapterPlan -> swiftAssociatedObject(
                    "chapterPlan",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(NovelChapterPlanId.Serializer, value.planID),
                        )
                    },
                )
                is RecentWrittenHighlights -> swiftAssociatedObject(
                    "recentWrittenHighlights",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelStateSnapshotId.Serializer,
                                value.snapshotID,
                            ),
                        )
                    },
                )
                is UpcomingArc -> swiftAssociatedObject(
                    "upcomingArc",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(NovelBranchId.Serializer, value.branchID),
                        )
                    },
                )
                is CurrentState -> swiftAssociatedObject(
                    "currentState",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelStateSnapshotId.Serializer,
                                value.snapshotID,
                            ),
                        )
                    },
                )
                is PendingManualState -> swiftAssociatedObject(
                    "pendingManualState",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelPendingOperationId.Serializer,
                                value.pendingID,
                            ),
                        )
                        put("chunkIndex", value.chunkIndex)
                    },
                )
                QuickStartSeed -> swiftAssociatedObject("quickStartSeed")
                is ChapterContext -> {
                    val clean = if (value.versionID == null) {
                        buildJsonObject { put("_0", kotlinx.serialization.json.JsonNull) }
                    } else {
                        buildJsonObject {
                            put(
                                "_0",
                                json.json.encodeToJsonElement(
                                    NovelChapterVersionId.Serializer,
                                    value.versionID,
                                ),
                            )
                        }
                    }
                    swiftAssociatedObject("chapterContext", clean)
                }
                is DiscussionArchive -> swiftAssociatedObject(
                    "discussionArchive",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(NovelMessageId.Serializer, value.messageID),
                        )
                        put("throughSequence", value.throughSequence)
                    },
                )
                is SessionMessage -> swiftAssociatedObject(
                    "sessionMessage",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(NovelMessageId.Serializer, value.messageID),
                        )
                    },
                )
                is StoryEvent -> swiftAssociatedObject(
                    "storyEvent",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(NovelEventId.Serializer, value.eventID),
                        )
                    },
                )
                is Material -> swiftAssociatedObject(
                    "material",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelMaterialRevisionId.Serializer,
                                value.revisionID,
                            ),
                        )
                    },
                )
                UserInput -> swiftAssociatedObject("userInput")
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelInjectionSectionKind {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            return when (caseName) {
                "fixedPrompt" -> FixedPrompt
                "polishPreference" -> PolishPreference
                "chapterPlan" -> ChapterPlan(
                    json.json.decodeFromJsonElement(
                        NovelChapterPlanId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "recentWrittenHighlights" -> RecentWrittenHighlights(
                    json.json.decodeFromJsonElement(
                        NovelStateSnapshotId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "upcomingArc" -> UpcomingArc(
                    json.json.decodeFromJsonElement(
                        NovelBranchId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "currentState" -> CurrentState(
                    json.json.decodeFromJsonElement(
                        NovelStateSnapshotId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "pendingManualState" -> PendingManualState(
                    pendingID = json.json.decodeFromJsonElement(
                        NovelPendingOperationId.Serializer,
                        associated.getValue("_0"),
                    ),
                    chunkIndex = associated.getValue("chunkIndex").jsonPrimitive.content.toInt(),
                )
                "quickStartSeed" -> QuickStartSeed
                "chapterContext" -> {
                    val raw = associated["_0"]
                    if (raw == null || raw is kotlinx.serialization.json.JsonNull) {
                        ChapterContext(null)
                    } else {
                        ChapterContext(
                            json.json.decodeFromJsonElement(NovelChapterVersionId.Serializer, raw),
                        )
                    }
                }
                "discussionArchive" -> DiscussionArchive(
                    messageID = json.json.decodeFromJsonElement(
                        NovelMessageId.Serializer,
                        associated.getValue("_0"),
                    ),
                    throughSequence = associated.getValue("throughSequence").jsonPrimitive.long,
                )
                "sessionMessage" -> SessionMessage(
                    json.json.decodeFromJsonElement(
                        NovelMessageId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "storyEvent" -> StoryEvent(
                    json.json.decodeFromJsonElement(NovelEventId.Serializer, associated.getValue("_0")),
                )
                "material" -> Material(
                    json.json.decodeFromJsonElement(
                        NovelMaterialRevisionId.Serializer,
                        associated.getValue("_0"),
                    ),
                )
                "userInput" -> UserInput
                else -> throw IllegalArgumentException("Unknown injection section kind: $caseName")
            }
        }
    }
}

// Keep Instant / UUID type aliases for model annotations without repeating serializer names.
typealias NovelDate = @Serializable(with = NovelSwiftDateSerializer::class) Instant
typealias NovelBareUuid = @Serializable(with = NovelBareUuidSerializer::class) UUID
