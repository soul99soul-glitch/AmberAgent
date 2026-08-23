package app.amber.feature.runtime

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P8-10 — one-time approval tokens for notification approve/deny/reply
 * actions (parity plan §P8-10 / §P8-11 L1).
 *
 * A token binds runId + conversationId + toolCallId + args digest. It is
 * single-use: consuming it (approve/deny/reply) removes it from the registry,
 * so replaying the same notification action is rejected — 不能只凭 notification
 * ID. Re-issuing for the same tool call (the live notification is rebuilt on
 * every status update) replaces the previous token, so only the action
 * currently on screen is valid.
 *
 * In-memory by design: a token only needs to live as long as the live
 * notification of its run. After process death the persisted WAITING_USER run
 * is recovered through the in-app approval card (P1-03), and tapping a stale
 * notification from a dead process resolves to nothing (fail closed).
 */
class NotificationApprovalTokenRegistry(
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What a token was issued for — the validation input of the decision. */
    data class Binding(
        val runId: String?,
        val conversationId: String,
        val toolCallId: String,
        val argsDigest: String,
        val issuedAtMs: Long,
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs - issuedAtMs > TOKEN_TTL_MS
    }

    private val tokens = ConcurrentHashMap<String, Binding>()

    /**
     * Issues a fresh token for [toolCallId] under [runId]. Any previous token
     * bound to the same (runId, toolCallId) is replaced — the notification is
     * rebuilt on every status update and only the latest action must work.
     */
    fun issue(
        runId: String?,
        conversationId: String,
        toolCallId: String,
        argsDigest: String,
    ): String {
        pruneExpired()
        tokens.entries.removeAll { entry ->
            entry.value.toolCallId == toolCallId && entry.value.runId == runId
        }
        val nowMs = now()
        val token = tokenString(runId, toolCallId, argsDigest)
        tokens[token] = Binding(
            runId = runId,
            conversationId = conversationId,
            toolCallId = toolCallId,
            argsDigest = argsDigest,
            issuedAtMs = nowMs,
        )
        return token
    }

    /**
     * Consumes the token (single-use). Returns null when the token is unknown,
     * already consumed or expired — the caller must not act on it.
     */
    fun consume(token: String): Binding? {
        val binding = tokens.remove(token) ?: return null
        return binding.takeIf { !it.isExpired(now()) }
    }

    /** Revokes every token of a conversation (notification dismissed). */
    fun revokeForConversation(conversationId: String) {
        tokens.entries.removeAll { it.value.conversationId == conversationId }
    }

    /** Test/observability hook. */
    fun clearForTest() {
        tokens.clear()
    }

    private fun pruneExpired() {
        val nowMs = now()
        tokens.entries.removeAll { it.value.isExpired(nowMs) }
    }

    companion object {
        /** Matches the waiting notification island timeout (§WAITING_ISLAND_TIMEOUT_SECONDS). */
        const val TOKEN_TTL_MS = 2 * 60 * 60 * 1000L

        /** Token string binds run + toolCall + digest + a random nonce. */
        internal fun tokenString(runId: String?, toolCallId: String, argsDigest: String): String {
            val seed = listOf(runId.orEmpty(), toolCallId, argsDigest, UUID.randomUUID().toString())
                .joinToString("|")
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * P8-10 — pure, side-effect-free validation of a notification decision against
 * its token binding and the conversation's current tool-call state. Kept
 * outside ChatService so the acceptance rules (replay, digest binding, run
 * ownership, still-pending) are JVM-testable without an Android runtime.
 */
object NotificationApprovalCheck {
    fun isValid(
        binding: NotificationApprovalTokenRegistry.Binding,
        intentRunId: String?,
        conversationId: String,
        toolCallId: String,
        currentArgsDigest: String?,
        toolStillPending: Boolean,
    ): Boolean {
        // Token was issued for a different conversation / tool call.
        if (binding.conversationId != conversationId) return false
        if (binding.toolCallId != toolCallId) return false
        // Ownership: the action must arrive for the same run the token was
        // issued to; a stale notification of an earlier run is rejected.
        if (binding.runId != intentRunId) return false
        // Digest binding: the call the user saw must equal the call now in the
        // conversation; null digest means the tool call is gone.
        if (currentArgsDigest == null || currentArgsDigest != binding.argsDigest) return false
        // The tool must still be awaiting a decision (already approved/denied
        // or executed → replay must not re-apply).
        if (!toolStillPending) return false
        return true
    }
}
