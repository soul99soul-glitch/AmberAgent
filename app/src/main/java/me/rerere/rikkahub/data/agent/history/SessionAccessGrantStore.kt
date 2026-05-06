package me.rerere.rikkahub.data.agent.history

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class SessionAccessGrantStore {
    private val grants = ConcurrentHashMap<String, SessionAccessGrant>()

    fun create(
        sessionIds: Collection<String>,
        maxChars: Int,
        purpose: String,
        sourceConversationId: String,
        assignedSubagentRunId: String? = null,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): SessionAccessGrant {
        val distinctIds = sessionIds.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_SESSIONS)
        require(distinctIds.isNotEmpty()) { "SessionAccessGrant requires at least one session id." }
        val now = Instant.now().toEpochMilli()
        val grant = SessionAccessGrant(
            grantId = Uuid.random().toString(),
            sessionIds = distinctIds.toSet(),
            maxSessions = distinctIds.size,
            maxChars = maxChars.coerceIn(1_000, MAX_GRANT_CHARS),
            purpose = purpose.take(1_000),
            expiresAt = now + ttlMs.coerceIn(1L, DEFAULT_TTL_MS),
            sourceConversationId = sourceConversationId,
            assignedSubagentRunId = assignedSubagentRunId,
        )
        grants[grant.grantId] = grant
        return grant
    }

    fun validate(grantId: String, sessionId: String, requestedChars: Int): GrantValidation {
        val grant = grants[grantId] ?: return GrantValidation.Denied("Unknown or expired session access grant.")
        val now = Instant.now().toEpochMilli()
        if (now > grant.expiresAt) {
            grants.remove(grantId)
            return GrantValidation.Denied("Session access grant has expired.")
        }
        if (sessionId !in grant.sessionIds) {
            return GrantValidation.Denied("Session is outside the grant scope.")
        }
        val remaining = grant.maxChars - grant.usedChars
        if (remaining <= 0) {
            return GrantValidation.Denied("Session access grant character budget is exhausted.")
        }
        return GrantValidation.Allowed(grant, requestedChars.coerceAtMost(remaining))
    }

    fun recordUse(grantId: String, chars: Int) {
        grants.computeIfPresent(grantId) { _, grant ->
            grant.copy(usedChars = (grant.usedChars + chars.coerceAtLeast(0)).coerceAtMost(grant.maxChars))
        }
    }

    fun get(grantId: String): SessionAccessGrant? = grants[grantId]

    sealed interface GrantValidation {
        data class Allowed(val grant: SessionAccessGrant, val allowedChars: Int) : GrantValidation
        data class Denied(val reason: String) : GrantValidation
    }

    private companion object {
        private const val DEFAULT_TTL_MS = 30 * 60_000L
        private const val MAX_GRANT_CHARS = 120_000
        private const val MAX_SESSIONS = 24
    }
}
