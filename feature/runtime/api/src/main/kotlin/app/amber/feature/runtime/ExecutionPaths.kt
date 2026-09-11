package app.amber.feature.runtime

import app.amber.feature.workspace.WorkspacePaths
import java.io.File

/**
 * Shared path canonicalization for the [ExecutionPolicy] sandbox (P1-6).
 *
 * The boundary gate and [ExecutionPolicy.narrow] MUST derive containment from
 * the SAME canonical form. They once diverged: narrow() compared roots by
 * literal string prefix while the gate compared `File.canonicalPath` forms, so
 * a child root carrying `..` (e.g. `/a/sandbox/../../../data/data/x`) survived
 * narrowing and widened the parent sandbox. Both sides now go through this
 * single helper — pure JVM, no Android types.
 *
 * Semantics (pinned by ExecutionPolicyGateTest):
 * - `/workspace/...` arguments and bare workspace-relative arguments normalize
 *   lexically via [WorkspacePaths.normalize], which REJECTS `..` outright.
 * - Other absolute paths canonicalize via `File.canonicalPath` (resolves
 *   symlinked ancestors such as `/tmp` -> `/private/tmp` and eliminates `..`).
 * - Blank, `..`-traversing, or otherwise uncanonicalizable inputs yield null
 *   and every consumer must fail closed on null.
 */
object ExecutionPaths {

    /** Canonical surface root workspace-relative arguments are anchored to. */
    const val WORKSPACE_ROOT = "/workspace"

    /**
     * Canonical form of a configured allowed root; null when the root cannot
     * be normalized. Unparseable roots are dropped (fail closed) rather than
     * compared verbatim.
     */
    fun canonicalRoot(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/")) {
            if (isWorkspacePath(trimmed)) {
                val relative = normalizeWorkspace(trimmed) ?: return null
                return if (relative == ".") WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative"
            }
            return runCatching { File(trimmed).canonicalPath }.getOrNull()
        }
        // Relative root: anchored under the workspace like a tool argument.
        val relative = normalizeWorkspace(trimmed) ?: return null
        return if (relative == ".") WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative"
    }

    /**
     * Canonical absolute form of a path argument for containment checking.
     * A null [raw] means the tool's schema default, which is the workspace
     * root. Returns null when the argument is present but blank, traverses
     * with `..`, or cannot be canonicalized.
     */
    fun canonicalPath(raw: String?): String? {
        if (raw == null) return WORKSPACE_ROOT
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null // present but blank: fail closed
        if (trimmed.startsWith("/")) {
            if (isWorkspacePath(trimmed)) {
                val relative = normalizeWorkspace(trimmed) ?: return null
                return if (relative == ".") WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative"
            }
            return runCatching { File(trimmed).canonicalPath }.getOrNull()
        }
        // Bare relative argument: the workspace file tools treat it as
        // workspace-relative; normalize lexically (rejects `..`).
        val relative = normalizeWorkspace(trimmed) ?: return null
        return if (relative == ".") WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative"
    }

    /** `root` itself or anything under `root/`, compared canonical-to-canonical. */
    fun isUnder(candidate: String, root: String): Boolean =
        candidate == root || candidate.startsWith("$root/")

    private fun isWorkspacePath(trimmed: String): Boolean =
        trimmed == WORKSPACE_ROOT || trimmed.startsWith("$WORKSPACE_ROOT/")

    private fun normalizeWorkspace(path: String): String? =
        runCatching { WorkspacePaths.normalize(path) }.getOrNull()
}
