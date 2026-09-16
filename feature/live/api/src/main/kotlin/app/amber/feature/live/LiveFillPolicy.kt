package app.amber.feature.live

/**
 * 填入决策（蓝图 §7.2 P0-5 仲裁契约的纯逻辑核，供 JVM 定点测试）：
 * 平台探测（窗口/文本读取）由 LiveModeManager 完成后把事实传入。
 * 冲突一律拒绝（降级复制），不排队、不自动重试。
 */
object LiveFillPolicy {

    enum class Decision {
        /** 校验全过，执行写入（写入后回读不符仍由执行层熔断降级）。 */
        FILL,

        /** 目标框已有非草稿文本：需用户明确选择覆盖。 */
        CONFIRM,

        /** 卡片已脱离现场：拒绝填入，降级复制。 */
        COPY_STALE,

        /** 其余一切不满足（非白名单/已熔断/目标丢失/现场不匹配）：复制。 */
        COPY,
    }

    fun decide(
        cardStale: Boolean,
        scene: LiveScene,
        denied: Boolean,
        targetPresent: Boolean,
        contextMatches: Boolean,
        existingText: String?,
        draft: String,
        confirmed: Boolean,
    ): Decision = when {
        cardStale -> Decision.COPY_STALE
        scene != LiveScene.CHAT || denied -> Decision.COPY
        !targetPresent || !contextMatches -> Decision.COPY
        !existingText.isNullOrBlank() && existingText != draft && !confirmed -> Decision.CONFIRM
        else -> Decision.FILL
    }
}
