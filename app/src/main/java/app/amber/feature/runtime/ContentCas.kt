package app.amber.feature.runtime

import app.amber.feature.tools.Capability
import java.security.MessageDigest

/**
 * Minimal shared digest/CAS primitives for the Phase 2 governance flows
 * (docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md
 * §P2-04 / §P2-06 / §P2-07).
 *
 * The three CAS patterns (skill promotion, memory edit/delete, soul import)
 * share the same shape: an approval binds a digest over the *base* (the
 * current version the user saw) plus the *candidate* (what will replace it),
 * and apply re-reads both before swapping. [ContentDigest.bind] composes the
 * two digests into one stable approval key; [ApprovalGuard] remains the
 * single CAS-validation primitive (same-digest-required semantics).
 */
object ContentDigest {
    /** SHA-256 hex digest of a text payload. */
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Approval key over (base digest, candidate digest): if either side
     * changes after approval, the bound digest no longer matches and the
     * apply is rejected as stale. Separator is a NUL byte so concatenation
     * cannot collide with adjacent digests.
     */
    fun bind(baseDigest: String, candidateDigest: String): String =
        sha256("$baseDigest\u0000$candidateDigest")
}

/**
 * P2-04/P2-06/P2-07 approval-ledger sink shared by the skill-promotion,
 * memory-CAS, soul-import and MCP-import (P2-05) flows — a single reusable
 * implementation backed by the P2-01 approval history
 * (CapabilityPermissionStore). Only digests are stored — never content.
 */
interface CasLedger {
    /** Record an approval bound to [digest] (approval history entry). */
    suspend fun recordApproval(entry: ApprovalHistoryEntry)

    /** Latest approved digest for a session, or null when not approved. */
    suspend fun approvedDigest(sessionId: String): String?

    /** Append an apply/rollback outcome to the session's audit trail. */
    suspend fun recordOutcome(sessionId: String, outcome: String)
}

/** Ledger backed by the capability-permission approval history store. */
class CapabilityBackedCasLedger(
    private val store: CapabilityPermissionStore,
) : CasLedger {
    override suspend fun recordApproval(entry: ApprovalHistoryEntry) {
        store.recordApproval(entry)
    }

    override suspend fun approvedDigest(sessionId: String): String? =
        store.approvalFor(runId = null, toolCallId = sessionId)
            ?.takeIf { it.decision == "approved" }
            ?.argsDigest

    override suspend fun recordOutcome(sessionId: String, outcome: String) {
        val latest = store.approvalFor(runId = null, toolCallId = sessionId) ?: return
        store.recordApproval(
            latest.copy(
                id = java.util.UUID.randomUUID().toString(),
                // P2-04/06/07: the outcome entry replaces the approval decision
                // — a rejected outcome must never satisfy the
                // decision == "approved" guard again (same digest must not be
                // re-appliable after a rejection).
                decision = if (outcome == "rejected") "denied" else latest.decision,
                outcome = outcome,
                approvedAtMs = System.currentTimeMillis(),
            )
        )
    }
}

/** Factory helpers for the common audit entries. */
object CasAudit {
    fun outcome(
        capability: Capability?,
        toolName: String,
        sessionId: String,
        runId: String?,
        digest: String,
        source: String,
        outcome: String,
        oldDigest: String? = null,
        newDigest: String? = null,
    ) = ApprovalHistoryEntry.approved(
        capability = capability,
        toolName = toolName,
        runId = runId,
        toolCallId = sessionId,
        effectId = null,
        argsDigest = digest,
        source = source,
        outcome = outcome,
        oldDigest = oldDigest,
        newDigest = newDigest,
    )
}
