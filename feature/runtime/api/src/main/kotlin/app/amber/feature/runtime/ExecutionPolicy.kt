package app.amber.feature.runtime

import app.amber.feature.tools.Capability
import kotlinx.serialization.Serializable

/**
 * Step 6 — separate sandbox from approval: per-run sandbox policy, enforced
 * at the ToolRuntime boundary on EVERY execution — independent of approval.
 * Approval is a one-time grant decision
 * (PermissionDecisionResolver / CapabilityPermissionStore); this is the
 * boundary that decides what an approved call may actually touch.
 *
 * v1 contract: the default policy ([permissive]) codifies NO new restriction —
 * existing in-tool confinement (WorkspacePaths, external-file roots, the
 * private-network block) keeps doing its job; the new capability is that a run
 * may carry a NARROWED policy and the boundary will enforce it. Known bypasses
 * and boundaries (v1):
 * - The MiniApp workspace bridge.
 * - JsCell's nested tool calls are NOT a bypass: the nested resolver admits
 *   the read-only `get_time_info` tool only (DataSourceModule), so no gated
 *   surface is reachable from inside a JS cell.
 * - `wm_eval` runs model-supplied JavaScript inside a logged-in WebView
 *   session; that page can `fetch` arbitrary hosts with session cookies, which
 *   the [allowedDomains] dimension cannot observe (wm_eval is reachable only
 *   behind the `network.connect` capability and a mandatory approval).
 * - `webview_open` / `wm_open` follow HTTP redirects, so their domain check
 *   constrains only the first hop; `http_request` / `download_file` disable
 *   redirect following. The inconsistency is a known v1 limitation.
 * - `webview_open_link` resolves a link `index` to its URL inside the tool
 *   body, outside the gate, so under an active [allowedDomains] dimension an
 *   index-only call is always denied (fail-closed v1 boundary). This is a
 *   DENIAL, not a bypass: the tool body never runs for an index-only call
 *   under an active domain dimension — only a call carrying the explicit
 *   `url` argument is checkable and can pass.
 *
 * Persistence boundary: a v1 [ExecutionPolicy] is process-local and produced
 * only in memory; production always runs [permissive]. If a real narrowing
 * producer ever ships, the policy MUST first be made durable — persisted along
 * the domain owner/CAS chain and restored with the run (repo AGENTS.md state
 * chain) — otherwise a cold start silently reverts every run to permissive.
 *
 * Every dimension is independently optional: a `null` dimension means
 * "unrestricted" (the universe), so [permissive] is a pure pass-through and a
 * policy only constrains the dimensions it explicitly sets. The sandbox side
 * is fail-closed: when a dimension is active and the relevant argument cannot
 * be parsed/normalized, the call is denied rather than let through.
 */
@Serializable
data class ExecutionPolicy(
    /**
     * Canonical absolute path roots file-family tools may touch. Paths are
     * matched as `root` itself or anything under `root/`; workspace-relative
     * arguments are canonicalized against the `/workspace` root. Unrestricted
     * when null (default). Tools that operate in a root domain this policy
     * does not model (the iCloud Vault, the novel workspace tree, the skill
     * library) are denied outright while this dimension is active — see
     * ExecutionPolicyGate's non-workspace-root list.
     */
    val allowedPathRoots: List<String>? = null,
    /**
     * Host allowlist for network tools (exact host or subdomain match, so
     * `api.example.com` matches `example.com` while `evil-example.com` does
     * not). Unrestricted when null (default).
     */
    val allowedDomains: List<String>? = null,
    /** false = terminal/shell tools are denied at the boundary. */
    val allowShell: Boolean = true,
    /**
     * System capabilities ([Capability] enum) a run may exercise, looked up
     * through the existing `capabilityForTool` mapping. Tools with no mapped
     * capability are not affected by this dimension. Unrestricted when null
     * (default).
     *
     * Coverage boundary: this dimension constrains only the capability
     * families `capabilityForTool` maps (files, network, shell-adjacent
     * config writes, and the device-permission families such as sms/call/
     * contacts/location/microphone/screen capture). Android runtime
     * permissions remain the outer enforcement for the device surfaces; this
     * list is an additional per-run allowlist, not a replacement. See the
     * persistence boundary note on this class before producing a narrowed
     * value in production.
     */
    val allowedSystemCapabilities: Set<Capability>? = null,
) {
    companion object {
        /** The default, byte-identical-with-history policy: nothing narrowed. */
        fun permissive() = ExecutionPolicy()
    }

    /**
     * Child-run narrowing: each dimension intersects; a null parent dimension
     * keeps the child's (null = universe absorbs whatever the child declares).
     * Path roots are canonicalized on both sides (same rule as the boundary
     * gate, [ExecutionPaths]) before comparison; a child root that cannot be
     * canonicalized or cannot be proven contained under a parent root is
     * dropped, and an empty intersection stays an empty list (deny-everything
     * in that dimension — never collapsed back to null/universe). Domains
     * keep only child domains equal to (or a subdomain of) a parent domain;
     * shell stays allowed only when both sides allow it.
     */
    fun narrow(child: ExecutionPolicy): ExecutionPolicy = ExecutionPolicy(
        allowedPathRoots = narrowPathRootsUnderParent(
            parent = allowedPathRoots,
            child = child.allowedPathRoots,
        ),
        allowedDomains = narrowUnderParent(
            parent = allowedDomains,
            child = child.allowedDomains,
            contained = ::isDomainUnderParent,
        ),
        allowShell = allowShell && child.allowShell,
        allowedSystemCapabilities = when {
            allowedSystemCapabilities == null -> child.allowedSystemCapabilities
            child.allowedSystemCapabilities == null -> allowedSystemCapabilities
            else -> allowedSystemCapabilities.intersect(child.allowedSystemCapabilities)
        },
    )
}

/**
 * Intersection of one list dimension: the child's list filtered to the entries
 * a non-null parent already admits; a null side is the universe, so a null
 * parent keeps the child verbatim and a null child keeps the parent.
 */
private fun <T> narrowUnderParent(
    parent: List<T>?,
    child: List<T>?,
    contained: (candidate: T, root: T) -> Boolean,
): List<T>? = when {
    child == null -> parent
    parent == null -> child
    else -> child.filter { candidate -> parent.any { contained(candidate, it) } }
}

/**
 * Path-root intersection with canonicalization (P1-6): both sides are
 * canonicalized with the same [ExecutionPaths] rule the boundary gate uses.
 * Child entries are kept verbatim when admitted (the gate re-canonicalizes at
 * check time), a child root that cannot be canonicalized — or whose canonical
 * form is not contained under any canonical parent root — is dropped, and a
 * fully disjoint intersection yields an EMPTY list (deny-all), never null.
 */
private fun narrowPathRootsUnderParent(
    parent: List<String>?,
    child: List<String>?,
): List<String>? = when {
    child == null -> parent
    parent == null -> child
    else -> {
        val parentRoots = parent.mapNotNull(ExecutionPaths::canonicalRoot)
        child.filter { candidate ->
            val canonical = ExecutionPaths.canonicalRoot(candidate)
            canonical != null && parentRoots.any { ExecutionPaths.isUnder(canonical, it) }
        }
    }
}

/** Exact host or subdomain match (`api.example.com` ⊆ `example.com`). */
private fun isDomainUnderParent(candidate: String, root: String): Boolean =
    candidate == root || candidate.endsWith(".$root")
