package app.amber.core.ai.mcp

import java.security.MessageDigest

/**
 * P2-02 MCP tool namespace (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §P2-02).
 *
 * MCP tools are exposed to the model as stable expanded names
 * `mcp__<server>__<tool>` instead of the old `mcp__<tool>` (which lost the
 * server dimension and collided across servers). The encoder:
 *
 *  - sanitizes illegal characters (provider function names only allow
 *    `[A-Za-z0-9_-]`) and truncates to the provider's 64-char limit;
 *  - resolves sanitization/truncation collisions with a **stable** SHA-256
 *    hash suffix (deterministic for a given configuration — never load-order
 *    numbering);
 *  - keeps the original server/tool metadata in the tool schema
 *    (`originalServerName` / `originalToolName`), so routing is reversible.
 *
 * Legacy compatibility: tool calls recorded in old conversations use
 * `mcp__<tool>`. [resolve] routes a unique match to the alias target and
 * rejects multi-server matches with an ambiguity so the model re-selects
 * explicitly.
 */
data class McpToolRef(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val description: String? = null,
    val inputSchema: app.amber.ai.core.InputSchema? = null,
    val needsApproval: Boolean = true,
)

sealed class McpToolNameResolution {
    /** Exactly one server exposes the tool — safe to route. */
    data class Unique(val ref: McpToolRef) : McpToolNameResolution()

    /** Multiple servers expose the same legacy tool name — must re-select. */
    data class Ambiguous(val refs: List<McpToolRef>) : McpToolNameResolution()

    data object NotFound : McpToolNameResolution()
}

object McpToolNamespace {
    const val PREFIX = "mcp__"

    /** OpenAI-compatible function names are capped at 64 chars. */
    const val MAX_NAME_LENGTH = 64

    private const val HASH_SUFFIX = "__h"
    private const val HASH_SUFFIX_LENGTH = 8
    /** Base (pre-suffix) budget so the hash suffix always fits in 64 chars. */
    private const val BASE_BUDGET = MAX_NAME_LENGTH - HASH_SUFFIX.length - HASH_SUFFIX_LENGTH
    private const val SEGMENT_MAX = 28

    private val VALID_SEGMENT_CHARS =
        ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '-')

    /** Replace characters that provider function-name rules reject. */
    fun sanitizeSegment(name: String): String =
        name.map { ch -> if (ch in VALID_SEGMENT_CHARS) ch else '_' }.joinToString("")

    /**
     * Stable batch encoding: the same configuration always maps to the same
     * expanded names regardless of the order the refs are presented in.
     * Collisions (sanitization or truncation) get a stable SHA-256 suffix.
     */
    fun encodeBatch(refs: List<McpToolRef>): Map<McpToolRef, String> {
        val base = refs.associateWith { baseName(it) }
        val firstPass = refs.associateWith { ref ->
            val name = base.getValue(ref)
            if (base.values.count { it == name } == 1) name else name + stableHashSuffix(ref)
        }
        // A second pass catches the pathological case where a suffixed name
        // equals another ref's unsuffixed base (e.g. a tool literally named
        // "x__h1234abcd"). Both get distinct suffixes, deterministically.
        val duplicates = firstPass.values.groupingBy { it }.eachCount()
        return refs.associateWith { ref ->
            val name = firstPass.getValue(ref)
            if (duplicates.getValue(name) == 1) {
                name
            } else {
                (name + stableHashSuffix(ref)).take(MAX_NAME_LENGTH)
            }
        }
    }

    /**
     * Resolve a model tool-call name to a concrete server/tool ref.
     *
     * 1. Exact expanded name (`mcp__server__tool`) — unique by construction.
     * 2. Legacy alias (`mcp__tool` from old sessions): unique match routes
     *    through the alias; multiple matches are rejected (the model must
     *    re-select via mcp_list/mcp_call_tool).
     */
    fun resolve(name: String, refs: List<McpToolRef>): McpToolNameResolution {
        val batch = encodeBatch(refs)
        val exact = refs.filter { batch[it] == name }
        if (exact.size == 1) return McpToolNameResolution.Unique(exact.first())
        if (!name.startsWith(PREFIX)) return McpToolNameResolution.NotFound
        val legacyTool = name.removePrefix(PREFIX)
        val matches = refs.filter { it.toolName == legacyTool }
        return when (matches.size) {
            0 -> McpToolNameResolution.NotFound
            1 -> McpToolNameResolution.Unique(matches.first())
            else -> McpToolNameResolution.Ambiguous(matches)
        }
    }

    /**
     * Human-readable display name, decoupled from the stable protocol name:
     * `mcp__server__tool` -> `server/tool` (hash suffix stripped). Routing
     * always uses the encoded name, never this display form.
     */
    fun displayName(name: String): String {
        if (!name.startsWith(PREFIX)) return name
        return name
            .removePrefix(PREFIX)
            .replace(Regex("__h[0-9a-f]{6,12}$"), "")
            .replace("__", "/")
    }

    private fun baseName(ref: McpToolRef): String {
        val server = sanitizeSegment(ref.serverName).take(SEGMENT_MAX)
        val tool = sanitizeSegment(ref.toolName).take(SEGMENT_MAX)
        return (PREFIX + server + "__" + tool).take(BASE_BUDGET)
    }

    private fun stableHashSuffix(ref: McpToolRef): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${ref.serverName}\u0000${ref.toolName}".toByteArray(Charsets.UTF_8))
        return HASH_SUFFIX + digest.joinToString("") { "%02x".format(it) }
            .take(HASH_SUFFIX_LENGTH)
    }
}

/** Stable SHA-256 hex digest (P2-02 namespace + P2-05 import digests). */
internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
