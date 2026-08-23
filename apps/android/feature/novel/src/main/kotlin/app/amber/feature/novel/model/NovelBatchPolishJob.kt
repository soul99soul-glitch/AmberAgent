package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.NovelSwiftDateSerializer
import app.amber.feature.novel.serialization.NovelTypedIdSerializer
import app.amber.feature.novel.serialization.normalizeUuidString
import app.amber.feature.novel.serialization.uuidStringLower
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable(with = NovelBatchPolishJobId.Serializer::class)
@JvmInline
value class NovelBatchPolishJobId(val rawValue: String) {
    init {
        require(rawValue == normalizeUuidString(rawValue))
    }

    override fun toString(): String = uuidStringLower(rawValue)

    companion object {
        fun generate(): NovelBatchPolishJobId =
            NovelBatchPolishJobId(UUID.randomUUID().toString().uppercase())

        fun parse(raw: String): NovelBatchPolishJobId =
            NovelBatchPolishJobId(normalizeUuidString(raw))
    }

    object Serializer : NovelTypedIdSerializer<NovelBatchPolishJobId>(
        serialName = "NovelBatchPolishJobId",
        wrap = ::NovelBatchPolishJobId,
        unwrap = { it.rawValue },
    )
}

@Serializable
enum class NovelBatchPolishJobStatus {
    @SerialName("running")
    Running,

    /** Interrupted (process death or user cancel); can be resumed from the cursor. */
    @SerialName("paused")
    Paused,

    @SerialName("completed")
    Completed,
}

/**
 * Durable cursor record for a foreground batch-polish run (P5-03).
 *
 * Unlike the ghostwrite job ledger this carries no lease/CAS machinery: the workspace
 * ViewModel owns execution in the foreground and advances [nextChapterIndex] after each
 * chapter. On process death the job stays non-terminal and the workspace offers a resume
 * entry that continues from the cursor (already-processed chapters are never re-run).
 * This record is app lifecycle state, not part of the package schema.
 */
@Serializable
data class NovelBatchPolishJobV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: NovelBatchPolishJobId,
    val projectID: NovelProjectId,
    val branchID: NovelBranchId,
    /** Chapter versions in batch order at start time; never reordered on resume. */
    val chapterVersionIDs: List<NovelChapterVersionId>,
    /** Cursor: index of the next chapter to process (chapters before it are done). */
    val nextChapterIndex: Int = 0,
    val status: NovelBatchPolishJobStatus = NovelBatchPolishJobStatus.Running,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val createdAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val updatedAt: Instant,
    @Serializable(with = NovelSwiftDateSerializer::class)
    val completedAt: Instant? = null,
) {
    val isTerminal: Boolean
        get() = status == NovelBatchPolishJobStatus.Completed

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
