package app.amber.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEngineTest {
    private fun engine() = LiveEngine(
        stableDelayMs = 1_000L,
        minAnalysisIntervalMs = 10_000L,
        backoffMs = 30_000L,
    )

    @Test
    fun `没有屏幕签名时不分析`() {
        val e = engine()
        assertEquals(LiveEngine.Decision.Wait("no_screen"), e.decide(nowMillis = 0L))
    }

    @Test
    fun `新签名要等稳定延迟`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        assertEquals(LiveEngine.Decision.Wait("settling"), e.decide(nowMillis = 500L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 1_000L))
    }

    @Test
    fun `同签名重复上报不重置稳定计时`() {
        val e = engine()
        assertTrue(e.onScreenSignature("s1", nowMillis = 0L))
        assertFalse(e.onScreenSignature("s1", nowMillis = 900L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 1_000L))
    }

    @Test
    fun `已分析过的签名跳过`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 1_000L)
        e.onAnalysisSucceeded("s1")
        assertEquals(LiveEngine.Decision.Wait("unchanged"), e.decide(nowMillis = 20_000L))
    }

    @Test
    fun `冷却期内不分析`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 1_000L)
        e.onAnalysisSucceeded("s1")
        e.onScreenSignature("s2", nowMillis = 2_000L)
        assertEquals(LiveEngine.Decision.Wait("cooldown"), e.decide(nowMillis = 5_000L))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 11_000L))
    }

    @Test
    fun `force 绕过冷却与去重但不绕过退避`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onAnalysisStarted(nowMillis = 100L)
        e.onAnalysisSucceeded("s1")
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 200L, force = true))
        e.onRetryableFailure(nowMillis = 300L)
        assertEquals(LiveEngine.Decision.Wait("backoff"), e.decide(nowMillis = 400L, force = true))
        assertEquals(LiveEngine.Decision.Analyze, e.decide(nowMillis = 30_301L, force = true))
    }

    @Test
    fun `成功清除退避`() {
        val e = engine()
        e.onScreenSignature("s1", nowMillis = 0L)
        e.onRetryableFailure(nowMillis = 100L)
        e.onAnalysisSucceeded("s1")
        assertEquals(0L, e.backoffUntilMillis())
    }
}
