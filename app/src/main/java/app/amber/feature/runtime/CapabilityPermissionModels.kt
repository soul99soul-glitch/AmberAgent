package app.amber.feature.runtime

import app.amber.feature.tools.Capability
import app.amber.feature.tools.CapabilityPolicy

/**
 * Scope of a capability-policy override.
 *
 * GLOBAL is kept as the inherited base policy. The other scopes are sparse
 * overrides and must be addressed by a real identifier; they are not a
 * general-purpose ACL.
 */
enum class CapabilityPermissionScope {
    GLOBAL,
    ASSISTANT,
    WORKSPACE,
    CONVERSATION,
    SESSION,
}

/** Runtime identifiers available to the permission resolver. */
data class CapabilityPermissionContext(
    val assistantId: String? = null,
    val workspaceId: String? = null,
    val conversationId: String? = null,
    /** Process-local current run; intentionally not persisted as a policy key. */
    val sessionId: String? = null,
)

/** A sparse policy map addressed by one concrete scope and identifier. */
data class CapabilityPermissionScopeKey(
    val scope: CapabilityPermissionScope,
    val id: String,
) {
    init {
        require(scope != CapabilityPermissionScope.GLOBAL) {
            "Global policies are stored in the legacy global map"
        }
        require(id.isNotBlank()) { "Scoped capability policy requires a non-blank id" }
    }
}

data class CapabilityPolicyMatch(
    val policy: CapabilityPolicy,
    val scope: CapabilityPermissionScope,
)

/**
 * Snapshot passed to one generation run.
 *
 * [policies] is the existing global map and remains source-compatible with
 * the pre-scope capability permission implementation. [scopedPolicies] only
 * stores explicitly set capability values; an absent value inherits from the
 * next less-specific scope.
 */
data class CapabilityPermissionState(
    val policies: Map<Capability, CapabilityPolicy> = emptyMap(),
    val scopedPolicies: Map<CapabilityPermissionScopeKey, Map<Capability, CapabilityPolicy>> = emptyMap(),
) {
    /**
     * Resolve one capability across every scope that has a real matching ID.
     * The strictest policy wins (DISABLED > ASK > AUTO); equal policies use
     * the more-specific scope as the trace source. Missing values inherit.
     */
    fun policyFor(
        capability: Capability,
        context: CapabilityPermissionContext? = null,
    ): CapabilityPolicyMatch? {
        var match = policies[capability]?.let {
            CapabilityPolicyMatch(it, CapabilityPermissionScope.GLOBAL)
        }
        val scopedIds = context?.let {
            listOf(
                CapabilityPermissionScope.ASSISTANT to it.assistantId,
                CapabilityPermissionScope.WORKSPACE to it.workspaceId,
                CapabilityPermissionScope.CONVERSATION to it.conversationId,
                CapabilityPermissionScope.SESSION to it.sessionId,
            )
        }.orEmpty()
        scopedIds.forEach { (scope, id) ->
            val scopedId = id ?: return@forEach
            if (scopedId.isBlank()) return@forEach
            scopedPolicies[CapabilityPermissionScopeKey(scope, scopedId)]?.get(capability)?.let {
                val candidate = CapabilityPolicyMatch(it, scope)
                val current = match
                if (current == null || candidate.isStricterThan(current)) {
                    match = candidate
                }
            }
        }
        return match
    }

    private fun CapabilityPolicyMatch.isStricterThan(other: CapabilityPolicyMatch): Boolean {
        val thisRank = policy.strictnessRank()
        val otherRank = other.policy.strictnessRank()
        return thisRank > otherRank || (thisRank == otherRank && scope.ordinal > other.scope.ordinal)
    }

    private fun CapabilityPolicy.strictnessRank(): Int = when (this) {
        CapabilityPolicy.AUTO -> 1
        CapabilityPolicy.ASK -> 2
        CapabilityPolicy.DISABLED -> 3
    }
}
