package app.amber.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * P8-10 — one-time approval token for notification approve/deny/reply actions.
 *
 * Covers the JVM-executable part of the plan acceptance items:
 *  - 一次性 token 用一次后重放被拒 (single use, replay rejected)
 *  - token 与 run/toolCall/digest 绑定 (binding validation)
 *  - 过期 run 深链不执行旧审批 (revoke on run end / expiry → fail closed)
 */
class NotificationApprovalTokenRegistryTest {

    private val registry = NotificationApprovalTokenRegistry()

    @Test
    fun consumeReturnsBindingThenReplayIsRejected() {
        val token = registry.issue(
            runId = "run-1",
            conversationId = "conv-1",
            toolCallId = "call-1",
            argsDigest = "digest-1",
        )
        val binding = registry.consume(token)
        assertNotNull(binding)
        assertEquals("run-1", binding!!.runId)
        assertEquals("conv-1", binding.conversationId)
        assertEquals("call-1", binding.toolCallId)
        assertEquals("digest-1", binding.argsDigest)

        // Replay of the same token — approve tapped twice, or approve then deny
        // on the same notification — must resolve to nothing.
        assertNull(registry.consume(token))
    }

    @Test
    fun tokenBindsRunToolCallAndDigest() {
        val token = registry.issue(
            runId = "run-9",
            conversationId = "conv-2",
            toolCallId = "call-9",
            argsDigest = "digest-9",
        )
        val binding = registry.consume(token)!!
        assertEquals("run-9", binding.runId)
        assertEquals("conv-2", binding.conversationId)
        assertEquals("call-9", binding.toolCallId)
        assertEquals("digest-9", binding.argsDigest)
        // A token for another call never resolves against this binding.
        assertFalse(
            NotificationApprovalCheck.isValid(
                binding = binding,
                intentRunId = "run-9",
                conversationId = "conv-2",
                toolCallId = "call-OTHER",
                currentArgsDigest = "digest-9",
                toolStillPending = true,
            )
        )
    }

    @Test
    fun reissueForSameToolCallReplacesPreviousToken() {
        val first = registry.issue("run-1", "conv-1", "call-1", "digest-1")
        val second = registry.issue("run-1", "conv-1", "call-1", "digest-1")
        assertFalse(first == second)
        // The notification was rebuilt with a fresh token — the old action is dead.
        assertNull(registry.consume(first))
        assertNotNull(registry.consume(second))
    }

    @Test
    fun validationRejectsWrongRunWrongDigestAndResolvedTool() {
        val token = registry.issue("run-1", "conv-1", "call-1", "digest-1")
        val binding = registry.consume(token)!!

        // Wrong run (stale notification of an earlier run) → reject.
        assertFalse(
            NotificationApprovalCheck.isValid(binding, "run-0", "conv-1", "call-1", "digest-1", true)
        )
        // Missing run on the intent side → reject (ownership must be exact).
        assertFalse(
            NotificationApprovalCheck.isValid(binding, null, "conv-1", "call-1", "digest-1", true)
        )
        // Args changed since the user saw the call → reject (同一审批不能用于参数已经变化的调用).
        assertFalse(
            NotificationApprovalCheck.isValid(binding, "run-1", "conv-1", "call-1", "digest-CHANGED", true)
        )
        // Tool call no longer present in the conversation → reject.
        assertFalse(
            NotificationApprovalCheck.isValid(binding, "run-1", "conv-1", "call-1", null, true)
        )
        // Tool already approved/denied/answered → replay must not re-apply.
        assertFalse(
            NotificationApprovalCheck.isValid(binding, "run-1", "conv-1", "call-1", "digest-1", false)
        )
        // Wrong conversation → reject.
        assertFalse(
            NotificationApprovalCheck.isValid(binding, "run-1", "conv-OTHER", "call-1", "digest-1", true)
        )
        // The exact combination the notification produced → valid.
        assertTrue(
            NotificationApprovalCheck.isValid(binding, "run-1", "conv-1", "call-1", "digest-1", true)
        )
    }

    @Test
    fun revokeForConversationKillsTokensOfDismissedNotification() {
        val token = registry.issue("run-1", "conv-1", "call-1", "digest-1")
        registry.revokeForConversation("conv-1")
        assertNull(registry.consume(token))
    }

    @Test
    fun expiredTokenIsRejected() {
        val clock = AtomicLong(1_000L)
        val registryWithClock = NotificationApprovalTokenRegistry(now = { clock.get() })
        val token = registryWithClock.issue("run-1", "conv-1", "call-1", "digest-1")
        // Just before TTL — still valid.
        clock.set(1_000L + NotificationApprovalTokenRegistry.TOKEN_TTL_MS)
        assertNotNull(registryWithClock.consume(token))

        val token2 = registryWithClock.issue("run-1", "conv-1", "call-1", "digest-1")
        // Consumed more than TTL after issue — fail closed.
        clock.set(1_000L + 2 * NotificationApprovalTokenRegistry.TOKEN_TTL_MS + 1)
        assertNull(registryWithClock.consume(token2))
    }

    @Test
    fun expiredEntriesArePrunedOnIssue() {
        val clock = AtomicLong(1_000L)
        val registryWithClock = NotificationApprovalTokenRegistry(now = { clock.get() })
        val first = registryWithClock.issue("run-1", "conv-1", "call-1", "digest-1")
        clock.set(1_000L + NotificationApprovalTokenRegistry.TOKEN_TTL_MS + 1)
        registryWithClock.issue("run-2", "conv-2", "call-2", "digest-2")
        // 过期 token 在下一次 issue 时被剪除，重放被拒。
        assertNull(registryWithClock.consume(first))
    }

    @Test
    fun tokensForSameToolCallButDifferentRunsCoexist() {
        val a = registry.issue("run-1", "conv-1", "call-1", "digest-1")
        val b = registry.issue("run-2", "conv-1", "call-1", "digest-1")
        assertNotNull(registry.consume(a))
        assertNotNull(registry.consume(b))
    }
}
