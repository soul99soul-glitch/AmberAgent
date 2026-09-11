package app.amber.feature.runtime

import app.amber.feature.tools.capabilityForTool
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Step 6 — separate sandbox from approval: THE single sandbox gate. Given a
 * tool call and the run's [ExecutionPolicy], returns the denial reason, or
 * null when the call may proceed.
 *
 * This is deliberately independent of approval: it runs on every execution —
 * even when the approval side (PermissionDecisionResolver / user grant) said
 * ALLOW — and decides what an approved call may actually touch. With the
 * default [ExecutionPolicy.permissive] every check is dimension-gated and the
 * gate is a pure pass-through (v1 guardrail: no behavior change).
 *
 * The sandbox side is fail-closed: when a policy dimension is active and the
 * relevant argument cannot be parsed or normalized, the call is denied rather
 * than let through.
 *
 * Family → argument mapping below was read from each tool's real input schema:
 * - file: WorkspaceTools.kt (file_list/read/write/edit/search/move),
 *   ExternalFileTools.kt (external_file_list/read/write/delete),
 *   WorkspaceArtifactTools.kt (archive_list, archive_extract, archive_create,
 *   pdf_read, pdf_render_page, office_read, image_info, image_convert,
 *   ocr_image, download_file's write target), SkillsTools.kt (skill_import,
 *   skill_preview, skill_validate), SoulTools.kt (soul_preview, soul_import),
 *   FeishuOfficeTools.kt (officepro_* `workspace_paths` arrays and
 *   officepro_make_report's `output_path`), ModelCouncilTools.kt
 *   (model_council_make_report), ShareAccessTools.kt (share_file)
 * - network (args carry the outbound URL): WorkspaceArtifactTools.kt
 *   (http_request, download_file), SearchTools.kt (scrape_web), WebViewTools.kt
 *   (webview_open, webview_open_link), WebMountNavigationTools.kt (wm_open),
 *   WebMountFetchTools.kt (wm_signed_fetch), WebMountSiteTools.kt (wm_site_add),
 *   ScreenAutomationTools.kt (screen_open_url), IntentAccessTools.kt
 *   (intent_open's optional `data_uri`), FeishuOfficeTools.kt (officepro_open's
 *   optional `url`), DeepReadOpenTool.kt (deep_read_open's optional
 *   `source_url`)
 * - shell: TerminalTools.kt (the whole `terminal_` namespace)
 * - system: capabilityForTool (the PermissionDecisionResolver mapping source)
 *
 * Path canonicalization is shared with narrow() via [ExecutionPaths] (P1-6) so
 * the two can never diverge again.
 */
internal object ExecutionPolicyGate {

    private val WORKSPACE_ROOT = ExecutionPaths.WORKSPACE_ROOT

    private val OFFICEPRO_WORKSPACE_PATHS =
        listOf(PathArg("workspace_paths", optional = true, array = true))

    internal data class PathArg(
        val key: String,
        /**
         * true = the tool schema marks the argument optional; an absent
         * argument falls back to [defaultRoot], or touches no path at all
         * when [defaultRoot] is null.
         */
        val optional: Boolean = false,
        /** true = the argument is a JSON array of path strings. */
        val array: Boolean = false,
        /**
         * Canonical absolute root the tool reads/writes by default when the
         * argument is absent (e.g. `/workspace/extracted` for archive_extract).
         * Null = an absent argument touches nothing under this dimension.
         */
        val defaultRoot: String? = null,
    )

    /**
     * File-family tools → their path-bearing argument keys. All keyed
     * arguments are workspace-relative and are canonicalized against
     * [WORKSPACE_ROOT] via [ExecutionPaths]; `external_file_*` paths are
     * absolute and get java.io.File.canonicalPath.
     *
     * WARNING — coverage is opt-in: a path-bearing tool whose name is absent
     * from this table is NOT constrained by the
     * [ExecutionPolicy.allowedPathRoots] dimension at all (the gate never
     * sees it). Registering a new tool here, or in the fail-closed
     * [UNMODELED_ROOT_PATH_TOOLS] list, is the hard prerequisite for any
     * narrowed path policy to reach it. See the known-bypasses and boundaries
     * list in ExecutionPolicy.kt. Internal (not private) so
     * ExecutionPolicyCoveragePinTest can pin the key set against silent edits.
     */
    internal val FILE_PATH_ARGS: Map<String, List<PathArg>> = mapOf(
        "file_list" to listOf(PathArg("path", optional = true, defaultRoot = WORKSPACE_ROOT)),
        "file_read" to listOf(PathArg("path")),
        "file_write" to listOf(PathArg("path")),
        "file_edit" to listOf(PathArg("path")),
        "file_search" to listOf(PathArg("path", optional = true, defaultRoot = WORKSPACE_ROOT)),
        "file_move" to listOf(PathArg("source_path"), PathArg("target_path")),
        "external_file_list" to listOf(PathArg("path")),
        "external_file_read" to listOf(PathArg("path")),
        "external_file_write" to listOf(PathArg("path")),
        "external_file_delete" to listOf(PathArg("path")),
        // Workspace artifact tools with a path surface: under an active
        // allowedPathRoots dimension an ungated path tool would silently
        // bypass the roots, so they sit in the same family. Optional write
        // targets verify their schema-documented default root when absent.
        "archive_list" to listOf(PathArg("path")),
        "archive_extract" to listOf(
            PathArg("path"),
            // Default "extracted/<archive-name>" (WorkspaceArtifactTools.kt).
            PathArg("destination_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/extracted"),
        ),
        "archive_create" to listOf(PathArg("source_paths", array = true), PathArg("destination_path")),
        "pdf_read" to listOf(PathArg("path")),
        "pdf_render_page" to listOf(
            PathArg("path"),
            // Default "previews/<pdf>-page-<n>.png" (WorkspaceArtifactTools.kt).
            PathArg("destination_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/previews"),
        ),
        "office_read" to listOf(PathArg("path")),
        "image_info" to listOf(PathArg("path")),
        "image_convert" to listOf(PathArg("path"), PathArg("destination_path")),
        "ocr_image" to listOf(PathArg("path")),
        // P1-2: download_file's write target, default "downloads/<filename>".
        "download_file" to listOf(
            PathArg("workspace_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/downloads"),
        ),
        // P1-3: family tools whose arguments are workspace-relative.
        "skill_import" to listOf(PathArg("workspace_path")),
        "skill_preview" to listOf(PathArg("workspace_path")),
        // skill_validate also runs on an installed-skill `name` alone, so an
        // absent workspace_path touches nothing under /workspace.
        "skill_validate" to listOf(PathArg("workspace_path", optional = true)),
        // soul_preview / soul_import default to SOUL.md at the workspace root
        // (SoulImportTransaction.DEFAULT_SOUL_FILE).
        "soul_preview" to listOf(
            PathArg("workspace_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/SOUL.md"),
        ),
        "soul_import" to listOf(
            PathArg("workspace_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/SOUL.md"),
        ),
        // officepro_* read optional /workspace document lists (JSON arrays);
        // officepro_make_report also writes an optional report, default
        // "officepro/officepro-<template>-<stamp>.md".
        "officepro_capture_context" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_daily_radar" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_project_briefing" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_document_warroom" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_open_items_radar" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_meeting_closure" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_create_task_draft" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_create_base_record_draft" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_reply_draft" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_context_digest" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_project_context" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_project_report" to OFFICEPRO_WORKSPACE_PATHS,
        "officepro_make_report" to OFFICEPRO_WORKSPACE_PATHS +
            PathArg("output_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/officepro"),
        // Default "model-council/model-council-<stamp>.md".
        "model_council_make_report" to listOf(
            PathArg("output_path", optional = true, defaultRoot = "$WORKSPACE_ROOT/model-council"),
        ),
        // share_file reads a required workspace-relative path and hands it to
        // the Android share sheet.
        "share_file" to listOf(PathArg("path")),
    )

    /**
     * P1-3: path-bearing tools whose arguments address a root domain the v1
     * path policy does not model (each verified against its tool source). The
     * path dimension can only reason about /workspace-anchored and absolute
     * roots, so while it is active these tools are denied outright
     * (fail-closed) instead of being waved through ungated. Internal (not
     * private) so ExecutionPolicyCoveragePinTest can pin the exemption set.
     */
    internal val UNMODELED_ROOT_PATH_TOOLS: Map<String, String> = mapOf(
        // ICloudDriveTools.kt — Vault-relative paths under the configured
        // iCloud Drive Vault root, not /workspace.
        "icloud_list" to "the iCloud Drive Vault",
        "icloud_stat" to "the iCloud Drive Vault",
        "icloud_read" to "the iCloud Drive Vault",
        "icloud_write" to "the iCloud Drive Vault",
        "icloud_search" to "the iCloud Drive Vault",
        // NovelWorkspaceTools.kt — project-directory-relative paths in the
        // novel workspace tree, not /workspace.
        "novel_workspace_read" to "the novel workspace tree",
        "novel_workspace_write" to "the novel workspace tree",
        // SkillsTools.kt — use_skill's optional `path` is relative to the
        // installed skill directory (SkillReadBoundary containment), not
        // /workspace.
        "use_skill" to "the installed skill library",
    )

    /** URL-bearing network tools → which argument carries the outbound URL. */
    internal data class UrlArg(
        val key: String,
        /**
         * false = the schema marks the URL optional: an absent argument does
         * nothing outbound (dimension skips), and a non-http(s) scheme
         * (tel:, mailto:, app deep links) is not a web host the domain
         * dimension models (dimension skips too).
         */
        val required: Boolean,
    )

    /**
     * Network-family tools whose args carry the outbound URL to check.
     *
     * WARNING — coverage is opt-in: an outbound-URL tool whose name is absent
     * from this table is never checked by the
     * [ExecutionPolicy.allowedDomains] dimension (the gate skips the tool
     * entirely). Registering a new tool here is the hard prerequisite for a
     * narrowed domain policy to reach it. See the known-bypasses and
     * boundaries list in ExecutionPolicy.kt (e.g. `wm_eval`'s session-scoped
     * fetches stay invisible to this dimension even for mapped tools).
     * Internal (not private) so ExecutionPolicyCoveragePinTest can pin the
     * key set against silent edits.
     */
    internal val NETWORK_URL_ARGS: Map<String, UrlArg> = mapOf(
        "http_request" to UrlArg("url", required = true),
        "download_file" to UrlArg("url", required = true),
        "scrape_web" to UrlArg("url", required = true),
        "webview_open" to UrlArg("url", required = true),
        "webview_open_link" to UrlArg("url", required = true),
        "wm_open" to UrlArg("url", required = true),
        "wm_signed_fetch" to UrlArg("url", required = true),
        "wm_site_add" to UrlArg("url", required = true),
        // P1-4: outbound-URL tools that were missing from the domain table.
        // screen_open_url requires an http(s) URL (tool-enforced); the other
        // three accept absent arguments and/or non-web schemes.
        "screen_open_url" to UrlArg("url", required = true),
        "intent_open" to UrlArg("data_uri", required = false),
        "officepro_open" to UrlArg("url", required = false),
        "deep_read_open" to UrlArg("source_url", required = false),
    )

    /**
     * Shell family: the whole `terminal_` namespace (terminal_execute,
     * terminal_job_*, terminal_session_*, terminal_install_packages,
     * terminal_workspace_flush) — verified against TerminalTools.kt.
     */
    private fun isShellTool(toolName: String): Boolean = toolName.startsWith("terminal_")

    /**
     * Returns the human-readable denial reason, or null when the call passes
     * the sandbox. [input] is the raw tool-input JSON string (may be blank).
     */
    fun denialReason(
        toolName: String,
        input: String,
        policy: ExecutionPolicy,
        json: Json,
    ): String? {
        // Shell: name-only check, no argument parsing needed.
        if (!policy.allowShell && isShellTool(toolName)) {
            return "Tool '$toolName' is a terminal/shell tool and this run's execution policy " +
                "denies shell access (allowShell=false)."
        }
        // Locals: cross-module public properties do not smart cast.
        val allowedRoots = policy.allowedPathRoots
        if (allowedRoots != null) {
            UNMODELED_ROOT_PATH_TOOLS[toolName]?.let { rootDomain ->
                return "Tool '$toolName' operates in $rootDomain, a root domain this run's path " +
                    "policy does not model; denied while allowedPathRoots is narrowed " +
                    "(fail-closed v1 boundary)."
            }
            FILE_PATH_ARGS[toolName]?.let { pathArgs ->
                checkPathArgs(toolName, input, pathArgs, allowedRoots, json)?.let { return it }
            }
        }
        val allowedDomains = policy.allowedDomains
        if (allowedDomains != null && toolName in NETWORK_URL_ARGS) {
            checkUrlArg(toolName, input, allowedDomains, json)?.let { return it }
        }
        val allowedCapabilities = policy.allowedSystemCapabilities
        if (allowedCapabilities != null) {
            val capability = capabilityForTool(toolName)
            if (capability != null && capability !in allowedCapabilities) {
                return "Tool '$toolName' requires system capability '${capability.id}' which is not " +
                    "in this run's execution policy allowlist " +
                    "(${allowedCapabilities.joinToString { it.id }})."
            }
        }
        return null
    }

    private fun checkPathArgs(
        toolName: String,
        input: String,
        pathArgs: List<PathArg>,
        roots: List<String>,
        json: Json,
    ): String? {
        val args = parsedArgs(toolName, input, json)
            ?: return failClosed(toolName, "arguments could not be parsed as a JSON object", "path")
        // Compare canonical-to-canonical: File.canonicalPath resolves symlinked
        // ancestors (e.g. /tmp -> /private/tmp), so the roots must be
        // canonicalized with the same rule as the candidate paths.
        val canonicalRoots = roots.mapNotNull(ExecutionPaths::canonicalRoot)
        for (arg in pathArgs) {
            val element = args[arg.key]
            val rawValues: List<String?> = when {
                element == null && arg.optional -> {
                    // Absent optional argument: verify the schema's default
                    // root when it has one, otherwise it touches no path.
                    if (arg.defaultRoot == null) continue
                    listOf(null)
                }
                element == null -> return failClosed(
                    toolName,
                    "argument '${arg.key}' is missing",
                    "path",
                )
                arg.array -> {
                    val array = element as? JsonArray
                    val paths = array?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    when {
                        paths == null -> return failClosed(
                            toolName,
                            "argument '${arg.key}' must be a JSON array of strings",
                            "path",
                        )
                        paths.isEmpty() && arg.optional -> continue // [] reads no paths
                        paths.isEmpty() -> return failClosed(
                            toolName,
                            "argument '${arg.key}' must be a non-empty array of strings",
                            "path",
                        )
                    }
                    paths
                }
                else -> listOf((element as? JsonPrimitive)?.contentOrNull)
            }
            for (raw in rawValues) {
                val canonical = ExecutionPaths.canonicalPath(raw ?: arg.defaultRoot)
                    ?: return failClosed(
                        toolName,
                        "argument '${arg.key}' is blank or cannot be normalized",
                        "path",
                    )
                if (canonicalRoots.none { root -> ExecutionPaths.isUnder(canonical, root) }) {
                    return "Path '${raw ?: "(default)"}' ('$canonical') is outside this run's " +
                        "allowed path roots (${roots.joinToString()})."
                }
            }
        }
        return null
    }

    private fun checkUrlArg(
        toolName: String,
        input: String,
        domains: List<String>,
        json: Json,
    ): String? {
        val urlArg = NETWORK_URL_ARGS[toolName] ?: return null
        val args = parsedArgs(toolName, input, json)
            ?: return failClosed(toolName, "arguments could not be parsed as a JSON object", "domain")
        val url = (args[urlArg.key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (url == null) {
            // Optional-URL tools do nothing outbound without their argument;
            // required-URL tools fail closed on a missing/blank URL.
            if (!urlArg.required) return null
            return failClosed(toolName, "argument '${urlArg.key}' is missing or blank", "domain")
        }
        if (!urlArg.required) {
            // intent_open / officepro_open also carry non-web schemes (tel:,
            // mailto:, app deep links): the domain dimension models http(s)
            // hosts only, so other schemes skip this dimension.
            val scheme = runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()
            if (scheme != null && scheme != "http" && scheme != "https") return null
        }
        val host = runCatching { URI(url.trim()).host?.lowercase()?.takeIf { it.isNotBlank() } }
            .getOrNull()
            ?: return failClosed(toolName, "URL '$url' has no parseable host", "domain")
        val allowed = domains.any { domain ->
            val parent = domain.trim().lowercase()
            host == parent || host.endsWith(".$parent")
        }
        return if (allowed) {
            null
        } else {
            "Host '$host' is not in this run's allowed domains (${domains.joinToString()})."
        }
    }

    private fun parsedArgs(toolName: String, input: String, json: Json): JsonObject? =
        runCatching { json.parseToJsonElement(input.ifBlank { "{}" }) }.getOrNull() as? JsonObject

    private fun failClosed(toolName: String, detail: String, dimension: String): String =
        "Tool '$toolName' $detail; denied by this run's $dimension policy (fail-closed)."
}
