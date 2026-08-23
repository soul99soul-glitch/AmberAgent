package app.amber.feature.runtime

/**
 * P2-01 approval digest validation (parity plan §P2-01 #4).
 *
 * An approval binds to the exact args digest of the call the user saw:
 * `同一审批不能用于参数已经变化的调用`. If the model re-issues a tool call with
 * different parameters (same toolCallId) after the user approved the old
 * args, or the approved record is missing/denied, the approval is stale and
 * the tool must NOT execute.
 */
object ApprovalGuard {
    /**
     * @param approvedArgsDigest digest recorded when the user approved (null = never approved)
     * @param currentArgsDigest  digest of the args about to be executed
     * @param decision           "approved" / "denied" recorded at approval time
     */
    fun isValid(approvedArgsDigest: String?, currentArgsDigest: String, decision: String?): Boolean =
        decision == "approved" &&
            approvedArgsDigest != null &&
            approvedArgsDigest == currentArgsDigest
}
