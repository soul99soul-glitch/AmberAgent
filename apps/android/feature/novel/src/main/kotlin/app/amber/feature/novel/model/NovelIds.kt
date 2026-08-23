package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelTypedIdSerializer
import app.amber.feature.novel.serialization.normalizeUuidString
import app.amber.feature.novel.serialization.uuidStringLower
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable(with = NovelProjectId.Serializer::class)
@JvmInline
value class NovelProjectId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue)) {
            "NovelProjectId must be uppercase UUID, got $rawValue"
        }
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelProjectId = NovelProjectId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelProjectId = NovelProjectId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelProjectId>(
        serialName = "NovelProjectId",
        wrap = { NovelProjectId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelBranchId.Serializer::class)
@JvmInline
value class NovelBranchId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelBranchId = NovelBranchId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelBranchId = NovelBranchId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelBranchId>(
        serialName = "NovelBranchId",
        wrap = { NovelBranchId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelSessionId.Serializer::class)
@JvmInline
value class NovelSessionId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelSessionId = NovelSessionId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelSessionId = NovelSessionId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelSessionId>(
        serialName = "NovelSessionId",
        wrap = { NovelSessionId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelMessageId.Serializer::class)
@JvmInline
value class NovelMessageId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelMessageId = NovelMessageId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelMessageId = NovelMessageId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelMessageId>(
        serialName = "NovelMessageId",
        wrap = { NovelMessageId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelCandidateId.Serializer::class)
@JvmInline
value class NovelCandidateId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelCandidateId = NovelCandidateId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelCandidateId = NovelCandidateId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelCandidateId>(
        serialName = "NovelCandidateId",
        wrap = { NovelCandidateId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelChapterId.Serializer::class)
@JvmInline
value class NovelChapterId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelChapterId = NovelChapterId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelChapterId = NovelChapterId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelChapterId>(
        serialName = "NovelChapterId",
        wrap = { NovelChapterId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelChapterVersionId.Serializer::class)
@JvmInline
value class NovelChapterVersionId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelChapterVersionId =
            NovelChapterVersionId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelChapterVersionId =
            NovelChapterVersionId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelChapterVersionId>(
        serialName = "NovelChapterVersionId",
        wrap = { NovelChapterVersionId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelMaterialId.Serializer::class)
@JvmInline
value class NovelMaterialId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelMaterialId = NovelMaterialId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelMaterialId = NovelMaterialId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelMaterialId>(
        serialName = "NovelMaterialId",
        wrap = { NovelMaterialId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelMaterialRevisionId.Serializer::class)
@JvmInline
value class NovelMaterialRevisionId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelMaterialRevisionId =
            NovelMaterialRevisionId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelMaterialRevisionId =
            NovelMaterialRevisionId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelMaterialRevisionId>(
        serialName = "NovelMaterialRevisionId",
        wrap = { NovelMaterialRevisionId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelStateSnapshotId.Serializer::class)
@JvmInline
value class NovelStateSnapshotId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelStateSnapshotId =
            NovelStateSnapshotId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelStateSnapshotId =
            NovelStateSnapshotId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelStateSnapshotId>(
        serialName = "NovelStateSnapshotId",
        wrap = { NovelStateSnapshotId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelEventId.Serializer::class)
@JvmInline
value class NovelEventId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelEventId = NovelEventId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelEventId = NovelEventId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelEventId>(
        serialName = "NovelEventId",
        wrap = { NovelEventId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelCheckpointId.Serializer::class)
@JvmInline
value class NovelCheckpointId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelCheckpointId =
            NovelCheckpointId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelCheckpointId = NovelCheckpointId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelCheckpointId>(
        serialName = "NovelCheckpointId",
        wrap = { NovelCheckpointId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelOperationId.Serializer::class)
@JvmInline
value class NovelOperationId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelOperationId =
            NovelOperationId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelOperationId = NovelOperationId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelOperationId>(
        serialName = "NovelOperationId",
        wrap = { NovelOperationId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelRunId.Serializer::class)
@JvmInline
value class NovelRunId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelRunId = NovelRunId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelRunId = NovelRunId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelRunId>(
        serialName = "NovelRunId",
        wrap = { NovelRunId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelPendingOperationId.Serializer::class)
@JvmInline
value class NovelPendingOperationId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelPendingOperationId =
            NovelPendingOperationId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelPendingOperationId =
            NovelPendingOperationId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelPendingOperationId>(
        serialName = "NovelPendingOperationId",
        wrap = { NovelPendingOperationId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelReceiptId.Serializer::class)
@JvmInline
value class NovelReceiptId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelReceiptId = NovelReceiptId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelReceiptId = NovelReceiptId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelReceiptId>(
        serialName = "NovelReceiptId",
        wrap = { NovelReceiptId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelProposalId.Serializer::class)
@JvmInline
value class NovelProposalId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelProposalId = NovelProposalId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelProposalId = NovelProposalId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelProposalId>(
        serialName = "NovelProposalId",
        wrap = { NovelProposalId(it) },
        unwrap = { it.rawValue },
    )
}

@Serializable(with = NovelChapterPlanId.Serializer::class)
@JvmInline
value class NovelChapterPlanId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelChapterPlanId = NovelChapterPlanId(UUID.randomUUID().toString().uppercase())
        fun parse(raw: String): NovelChapterPlanId = NovelChapterPlanId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelChapterPlanId>(
        serialName = "NovelChapterPlanId",
        wrap = { NovelChapterPlanId(it) },
        unwrap = { it.rawValue },
    )
}
