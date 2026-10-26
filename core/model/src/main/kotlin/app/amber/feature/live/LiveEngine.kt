package app.amber.feature.live

/**
 * 伴随分析决策状态机（纯逻辑，时间由调用方注入）。
 * 收敛原 LiveModeManager 散落的 pendingHash/lastAnalyzedHash/backoff 字段与
 * LiveUiTreeProcessor.shouldAnalyze 的判断：
 *   屏幕签名变化 → 稳定延迟（防抖）→ 去重 → 冷却 → 分析；失败可退避。
 */
class LiveEngine(
    private val stableDelayMs: Long,
    private val minAnalysisIntervalMs: Long,
    private val backoffMs: Long = 30_000L,
) {
    sealed interface Decision {
        data object Analyze : Decision
        data class Wait(val reason: String) : Decision
    }

    var pendingSignature: String? = null
        private set
    private var pendingChangedAtMillis = 0L
    private var lastAnalyzedSignature: String? = null
    private var lastAnalysisAtMillis = Long.MIN_VALUE / 2
    private var backoffUntilMillisValue = 0L

    /** 上报当前屏幕签名。返回 true = 签名相对上次上报发生了变化。 */
    fun onScreenSignature(signature: String, nowMillis: Long): Boolean {
        if (signature == pendingSignature) return false
        pendingSignature = signature
        pendingChangedAtMillis = nowMillis
        return true
    }

    fun decide(nowMillis: Long, force: Boolean = false): Decision {
        val signature = pendingSignature ?: return Decision.Wait("no_screen")
        if (nowMillis < backoffUntilMillisValue) return Decision.Wait("backoff")
        if (force) return Decision.Analyze
        if (signature == lastAnalyzedSignature) return Decision.Wait("unchanged")
        if (nowMillis - pendingChangedAtMillis < stableDelayMs) return Decision.Wait("settling")
        if (nowMillis - lastAnalysisAtMillis < minAnalysisIntervalMs) return Decision.Wait("cooldown")
        return Decision.Analyze
    }

    fun onAnalysisStarted(nowMillis: Long) {
        lastAnalysisAtMillis = nowMillis
    }

    fun onAnalysisSucceeded(signature: String) {
        lastAnalyzedSignature = signature
        backoffUntilMillisValue = 0L
    }

    fun onRetryableFailure(nowMillis: Long) {
        backoffUntilMillisValue = nowMillis + backoffMs
    }

    fun backoffUntilMillis(): Long = backoffUntilMillisValue
}
