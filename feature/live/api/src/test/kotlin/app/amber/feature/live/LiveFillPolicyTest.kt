package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** P0-5 仲裁契约（蓝图 §7.2）的决策表锁定：每条规则一个用例，顺序即优先级。 */
class LiveFillPolicyTest {

    private fun decide(
        cardStale: Boolean = false,
        scene: LiveScene = LiveScene.CHAT,
        denied: Boolean = false,
        targetPresent: Boolean = true,
        contextMatches: Boolean = true,
        existingText: String? = "",
        draft: String = "草稿",
        confirmed: Boolean = false,
    ) = LiveFillPolicy.decide(
        cardStale, scene, denied, targetPresent, contextMatches, existingText, draft, confirmed,
    )

    @Test
    fun `stale card is rejected to copy`() {
        assertEquals(LiveFillPolicy.Decision.COPY_STALE, decide(cardStale = true))
    }

    @Test
    fun `non chat scene falls back to copy`() {
        assertEquals(LiveFillPolicy.Decision.COPY, decide(scene = LiveScene.READING))
        assertEquals(LiveFillPolicy.Decision.COPY, decide(scene = LiveScene.OTHER))
    }

    @Test
    fun `denylisted package falls back to copy`() {
        assertEquals(LiveFillPolicy.Decision.COPY, decide(denied = true))
    }

    @Test
    fun `missing target or mismatched context falls back to copy`() {
        assertEquals(LiveFillPolicy.Decision.COPY, decide(targetPresent = false))
        assertEquals(LiveFillPolicy.Decision.COPY, decide(contextMatches = false))
    }

    @Test
    fun `existing foreign text requires confirmation`() {
        assertEquals(LiveFillPolicy.Decision.CONFIRM, decide(existingText = "对方已输入"))
    }

    @Test
    fun `confirmed overwrite fills and identical existing is idempotent fill`() {
        assertEquals(
            LiveFillPolicy.Decision.FILL,
            decide(existingText = "对方已输入", confirmed = true),
        )
        assertEquals(LiveFillPolicy.Decision.FILL, decide(existingText = "草稿"))
        assertEquals(LiveFillPolicy.Decision.FILL, decide(existingText = ""))
        assertEquals(LiveFillPolicy.Decision.FILL, decide(existingText = null, targetPresent = true))
    }

    @Test
    fun `confirmation cannot bypass earlier gates`() {
        // 确认态成立但现场已不匹配（用户切了会话）——仍拒绝。
        assertEquals(
            LiveFillPolicy.Decision.COPY,
            decide(existingText = "对方已输入", confirmed = true, contextMatches = false),
        )
    }
}
