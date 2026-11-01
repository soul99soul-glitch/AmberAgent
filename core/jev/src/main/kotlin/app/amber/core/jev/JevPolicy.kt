package app.amber.core.jev

/**
 * 逐用途置信阈值与判断语义版本（A3 校准基建）。
 *
 * 初值是实验拍定的（非供应商承诺）；shadow 校准数据（[JevCalibrationStore]）
 * 离线分析后在此更新，并必须递增 [CURRENT_POLICY_VERSION]——版本号参与
 * 缓存键（JevRuntimeConfig.policyVersion），阈值变更靠它让旧缓存失效。
 */
data class JevPolicy(
    val policyVersion: Int = CURRENT_POLICY_VERSION,
    /** TOOL_DISCOVERY：候选相关性 Noul 过阈才进排序。 */
    val toolDiscoveryMinRelevance: Double = 0.5,
    /** MEMORY_RECALL：记忆相关性 Noul 过阈才进排序。 */
    val memoryRecallMinRelevance: Double = 0.5,
    /** CONTEXT_SELECTION：块保留概率低于此值且无保留信号才省略。 */
    val contextSelectionKeepProbability: Double = 0.35,
    /** MODEL_ROUTING：模型适配 Noul 过阈才入席次排序。 */
    val modelRoutingMinSuitability: Double = 0.3,
    /** WEB_AUTOMATION：目标元素 Noul 过阈才可作为 click/type 目标。 */
    val webTargetThreshold: Double = 0.5,
    /** WEB_AUTOMATION：DONE 核验 Noul 过阈才承认完成。 */
    val webDoneVerifiedThreshold: Double = 0.6,
    /** WEB_AUTOMATION：Choice 自报信心低于此值交还主模型。 */
    val webLowConfidenceThreshold: Double = 0.5,
    /** PHONE_ACTIONS：safe Noul 低于此值不派发。真机导航探针 0.84–0.88；0.9 会误杀已标注的翻页按钮。 */
    val screenReadOnlyThreshold: Double = 0.8,
    /** PHONE_VERIFICATION：DONE 核验低于此值不得宣称完成。 */
    val screenDoneVerifiedThreshold: Double = 0.85,
    /** TOOL_RESULT_RETENTION：保留概率达到此值才跳过压缩清空。 */
    val toolResultRetentionKeepProbability: Double = 0.7,
    /** Automatic approval may only be tightened to manual approval. */
    val autoApprovalRiskThreshold: Double = 0.8,
    /** APPROVAL_TRIAGE：风险 Noul 过阈则建议至少 ASK，不能升为 ALLOW。 */
    val approvalTriageRiskThreshold: Double = 0.8,
    /** SUBAGENT_INTENT：角色 alignment Noul 低于此值视为偏离角色。 */
    val subagentIntentMinAlignment: Double = 0.7,
    /** SCRIPT_JUDGMENT：Choice 自报信心低于此值回落原文。 */
    val scriptJudgmentMinConfidence: Double = 0.6,
    /** Continue once when an unverified final reply claims completion. */
    val completionClaimThreshold: Double = 0.8,
) {
    companion object {
        /**
         * 判断语义版本：VERCEL 契约、预算档位或阈值变更时递增（参与缓存键）。
         * v3：VERCEL 从 evaluation-model 改为 decision-model（stateParts，spec 版本头改名）。
         * v4：可见用途 13 个；SCREEN_AUTOMATION 拆成 PHONE_VERIFICATION / PHONE_ACTIONS。
         */
        const val CURRENT_POLICY_VERSION = 4
    }
}
