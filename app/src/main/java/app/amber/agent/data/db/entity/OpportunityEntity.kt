package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

object OpportunityType {
    const val MEETING_PREP = "meeting_prep"
    const val DEPENDENCY_STALE = "dependency_stale"
}

object OpportunityStatus {
    const val SUGGESTED = "suggested"
    const val DISPATCHED = "dispatched"
    const val DISMISSED = "dismissed"
    const val EXPIRED = "expired"
    const val MUTED = "muted"
}

object ReferenceAnchorStatus {
    const val PROPOSED = "proposed"
    const val CONFIRMED = "confirmed"
    const val DRIFTED = "drifted"
    const val ACKED = "acked"
    const val DISMISSED = "dismissed"
    const val MUTED = "muted"
}

object ReferenceAnchorConfirmationMode {
    const val MANUAL = "manual"
    const val AUTO_HIGH_CONFIDENCE = "auto_high_confidence"
}

@Entity(
    tableName = "opportunity",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index("status"),
        Index("opportunity_type"),
        Index("trigger_at"),
        Index("due_at"),
    ],
)
data class OpportunityEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("dedupe_key")
    val dedupeKey: String,
    @ColumnInfo("opportunity_type")
    val opportunityType: String,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_ref")
    val sourceRef: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("evidence_json")
    val evidenceJson: String,
    @ColumnInfo("score_json")
    val scoreJson: String,
    @ColumnInfo("confidence")
    val confidence: Float,
    @ColumnInfo("status")
    val status: String = OpportunityStatus.SUGGESTED,
    @ColumnInfo("suggested_actions_json")
    val suggestedActionsJson: String = "[]",
    @ColumnInfo("due_at")
    val dueAt: Long? = null,
    @ColumnInfo("trigger_at")
    val triggerAt: Long? = null,
    @ColumnInfo("dispatched_task_id")
    val dispatchedTaskId: String? = null,
    @ColumnInfo("dismissed_reason")
    val dismissedReason: String? = null,
    @ColumnInfo("mute_scope")
    val muteScope: String? = null,
    @ColumnInfo("expires_at")
    val expiresAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
)

@Entity(
    tableName = "reference_anchor",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index("dependency_id"),
        Index("my_doc_ref"),
        Index("upstream_doc_ref"),
        Index("status"),
    ],
)
data class ReferenceAnchorEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("dedupe_key")
    val dedupeKey: String,
    @ColumnInfo("dependency_id")
    val dependencyId: String,
    @ColumnInfo("my_doc_ref")
    val myDocRef: String,
    @ColumnInfo("my_claim_text")
    val myClaimText: String,
    @ColumnInfo("upstream_doc_ref")
    val upstreamDocRef: String,
    @ColumnInfo("upstream_hint")
    val upstreamHint: String,
    @ColumnInfo("baseline_value")
    val baselineValue: String,
    @ColumnInfo("last_value")
    val lastValue: String? = null,
    @ColumnInfo("evidence_json")
    val evidenceJson: String = "{}",
    @ColumnInfo("score_json")
    val scoreJson: String = "{}",
    @ColumnInfo("match_confidence")
    val matchConfidence: Float,
    @ColumnInfo("status")
    val status: String = ReferenceAnchorStatus.PROPOSED,
    @ColumnInfo("confirmation_mode")
    val confirmationMode: String = ReferenceAnchorConfirmationMode.MANUAL,
    @ColumnInfo("last_checked_at")
    val lastCheckedAt: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
)

fun stableOpportunityId(dedupeKey: String): String = sha256Hex(dedupeKey).take(32)

fun stableReferenceAnchorId(dedupeKey: String): String = sha256Hex(dedupeKey).take(32)

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
