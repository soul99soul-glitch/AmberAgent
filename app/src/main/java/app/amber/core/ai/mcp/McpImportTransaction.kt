package app.amber.core.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.feature.runtime.ApprovalGuard
import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CasLedger
import app.amber.feature.tools.isPrivateNetworkTarget

/**
 * P2-05 MCP import safety transaction (docs/plans/2026-08-13-android-ios-
 * capability-parity-closure-plan.md §P2-05).
 *
 * Prepare → Approve → Apply → Audit:
 *
 * 1. **Prepare** — strict transport parsing (unknown transports fail closed,
 *    never degrade to HTTP), URL/network-policy/header-structure validation,
 *    a redacted preview (origin only, header **names** only) and a candidate
 *    digest over the full canonical candidate (header values included in the
 *    digest so any content change invalidates the approval — the digest is a
 *    hash, never plaintext).
 * 2. **Approve** — binds the candidate digest via [ApprovalGuard] semantics
 *    and records it in the approval history (reusing the P2-01 audit trail).
 * 3. **Apply** — re-reads the input and verifies the digest (content changed
 *    → stale, re-preview required), rejects the whole batch on any name
 *    conflict (never a partial import), preflights every candidate with a
 *    temporary client (connect + listTools) and only then publishes the whole
 *    batch in one settings update.
 * 4. **Audit** — records digest, server count, risk, approval and outcome in
 *    the approval history.
 *
 * Note: the final settings publish is one DataStore edit (SettingsAggregator
 * update); this transaction adds approval binding, connect preflight,
 * conflict semantics and pre-publish verification — not a database ACID layer.
 */
enum class McpImportTransport {
    STREAMABLE_HTTP,
    SSE,
}

/** One validated server entry parsed from the import file. */
data class McpImportCandidate(
    val serverName: String,
    val transport: McpImportTransport,
    val url: String,
    val headers: List<Pair<String, String>>,
) {
    fun toMcpServerConfig(): McpServerConfig {
        val common = McpCommonOptions(name = serverName, headers = headers)
        return when (transport) {
            McpImportTransport.STREAMABLE_HTTP ->
                McpServerConfig.StreamableHTTPServer(commonOptions = common, url = url)
            McpImportTransport.SSE ->
                McpServerConfig.SseTransportServer(commonOptions = common, url = url)
        }
    }
}

/** Redacted preview row: never contains header values or URL paths/query. */
data class McpImportServerPreview(
    val serverName: String,
    val transport: McpImportTransport,
    /** scheme://host[:port] — credentials, path and query are stripped. */
    val origin: String,
    val headerNames: List<String>,
    /** "high" (headers / private network target) | "normal". */
    val risk: String,
    val note: String? = null,
)

data class McpImportPreview(
    /** SHA-256 of the canonical candidate (header values included, hash only). */
    val digest: String,
    val servers: List<McpImportServerPreview>,
    /** Batch risk: "high" | "normal". */
    val risk: String,
    val serverCount: Int,
    val headerNameCount: Int,
)

sealed class McpImportPreparation {
    data class Ready(
        val preview: McpImportPreview,
        val candidates: List<McpImportCandidate>,
    ) : McpImportPreparation()

    data class Rejected(val errors: List<String>) : McpImportPreparation()
}

sealed class McpImportApplyResult {
    data class Applied(val serverCount: Int, val toolCount: Int) : McpImportApplyResult()

    /** Input changed since preview — the approval is stale; re-preview required. */
    data class Stale(val reason: String) : McpImportApplyResult()

    data class Rejected(val errors: List<String>) : McpImportApplyResult()
}

class McpImportTransaction(
    private val preflight: McpConnectPreflight,
    private val approvalLedger: CasLedger,
    private val existingServerNames: () -> Set<String>,
    private val publish: suspend (List<McpServerConfig>) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Stage 1: strict parse + validation + redacted preview + digest. */
    fun prepare(rawJson: String): McpImportPreparation {
        val errors = mutableListOf<String>()

        val duplicates = detectDuplicateMcpServerNames(rawJson)
        if (duplicates.isNotEmpty()) {
            return McpImportPreparation.Rejected(
                duplicates.map { "Duplicate MCP server name in the batch: '$it'" }
            )
        }

        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { return McpImportPreparation.Rejected(listOf("Invalid JSON: ${it.message}")) }
        val mcpServers = root["mcpServers"]?.jsonObject
            ?: return McpImportPreparation.Rejected(listOf("Missing 'mcpServers' object in the input"))

        val candidates = mcpServers.mapNotNull { (name, element) ->
            parseCandidate(name, element, errors)
        }
        if (errors.isNotEmpty()) return McpImportPreparation.Rejected(errors)
        if (candidates.isEmpty()) {
            return McpImportPreparation.Rejected(listOf("No MCP servers found in the input"))
        }

        val previews = candidates.map { candidate ->
            val privateTarget = candidate.url.isPrivateNetworkTarget()
            val risk = if (candidate.headers.isNotEmpty() || privateTarget) "high" else "normal"
            McpImportServerPreview(
                serverName = candidate.serverName,
                transport = candidate.transport,
                origin = redactedOrigin(candidate.url),
                headerNames = candidate.headers.map { it.first },
                risk = risk,
                note = if (privateTarget) {
                    "URL targets a private/local network address"
                } else {
                    null
                },
            )
        }
        val batchRisk = if (previews.any { it.risk == "high" }) "high" else "normal"
        return McpImportPreparation.Ready(
            preview = McpImportPreview(
                digest = candidateDigest(candidates),
                servers = previews,
                risk = batchRisk,
                serverCount = candidates.size,
                headerNameCount = candidates.sumOf { it.headers.size },
            ),
            candidates = candidates,
        )
    }

    /** Stage 2: bind the approval to the candidate digest (approval history). */
    suspend fun approve(preview: McpImportPreview, sessionId: String, source: String = "user") {
        approvalLedger.recordApproval(
            ApprovalHistoryEntry.approved(
                capability = app.amber.feature.tools.Capability.MCP_IMPORT,
                toolName = "mcp_import",
                runId = null,
                toolCallId = sessionId,
                effectId = null,
                argsDigest = preview.digest,
                source = source,
                serverCount = preview.serverCount,
                riskLabel = preview.risk,
                outcome = "pending",
            )
        )
    }

    /**
     * Stage 3: re-read + digest verification + batch conflict check +
     * connect preflight + one publish.
     */
    suspend fun apply(rawJson: String, sessionId: String): McpImportApplyResult {
        val preparation = prepare(rawJson)
        if (preparation is McpImportPreparation.Rejected) {
            return McpImportApplyResult.Rejected(preparation.errors)
        }
        val ready = preparation as McpImportPreparation.Ready
        val candidates = ready.candidates

        // Digest verification: the file must be unchanged since the preview
        // the user approved (ApprovalGuard semantics).
        val approvedDigest = approvalLedger.approvedDigest(sessionId)
        if (!ApprovalGuard.isValid(approvedDigest, ready.preview.digest, "approved")) {
            return McpImportApplyResult.Stale(
                "The import file changed after the preview; re-preview and approve it again."
            )
        }

        // Existing-name conflict check — never import a subset.
        val existing = existingServerNames()
        val conflicts = candidates.map { it.serverName }.filter { it in existing }
        if (conflicts.isNotEmpty()) {
            return reject(candidates, ready.preview, sessionId,
                conflicts.map { "MCP server '$it' already exists in the configuration; remove it first or delete the server before importing again" })
        }

        // Connect preflight: every server must connect and list tools. Any
        // failure rejects the whole batch — nothing is published.
        val toolCounts = mutableListOf<Int>()
        val preflightErrors = mutableListOf<String>()
        candidates.forEach { candidate ->
            try {
                toolCounts += preflight.connectAndListTools(candidate).size
            } catch (error: Throwable) {
                preflightErrors += "Connect preflight failed for '${candidate.serverName}': ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (preflightErrors.isNotEmpty()) {
            return reject(candidates, ready.preview, sessionId, preflightErrors)
        }

        // Publish the whole batch in one settings update.
        publish(candidates.map { it.toMcpServerConfig() })
        approvalLedger.recordOutcome(sessionId, "applied")
        return McpImportApplyResult.Applied(serverCount = candidates.size, toolCount = toolCounts.sum())
    }

    private suspend fun reject(
        candidates: List<McpImportCandidate>,
        preview: McpImportPreview,
        sessionId: String,
        errors: List<String>,
    ): McpImportApplyResult {
        approvalLedger.recordOutcome(sessionId, "rejected")
        return McpImportApplyResult.Rejected(errors)
    }

    private fun parseCandidate(
        name: String,
        element: kotlinx.serialization.json.JsonElement,
        errors: MutableList<String>,
    ): McpImportCandidate? {
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val transport = when (type) {
            "streamable_http" -> McpImportTransport.STREAMABLE_HTTP
            "sse" -> McpImportTransport.SSE
            else -> {
                errors += "Server '$name' has unsupported transport '${type ?: "missing"}'; " +
                    "only streamable_http and sse are supported (fail closed)"
                return null
            }
        }

        val url = obj["url"]?.jsonPrimitive?.contentOrNull
        if (url.isNullOrBlank()) {
            errors += "Server '$name' is missing a url"
            return null
        }
        val urlError = validateUrl(name, url)
        if (urlError != null) {
            errors += urlError
            return null
        }

        val headers = when (val raw = obj["headers"]) {
            null -> emptyList()
            is JsonObject -> raw.mapNotNull { (key, value) ->
                if (key.isBlank()) {
                    errors += "Server '$name' has a blank header name"
                    null
                } else {
                    val text = (value as? JsonPrimitive)?.contentOrNull
                        ?: run {
                            errors += "Server '$name' header '$key' must be a string value"
                            return@mapNotNull null
                        }
                    key to text
                }
            }
            else -> {
                errors += "Server '$name' headers must be a JSON object"
                return null
            }
        }
        return McpImportCandidate(
            serverName = name,
            transport = transport,
            url = url,
            headers = headers,
        )
    }

    private fun validateUrl(name: String, url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
            ?: return "Server '$name' has an invalid url: $url"
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Server '$name' has an unsupported url scheme '${scheme ?: "missing"}'; only http/https"
        }
        if (uri.host.isNullOrBlank()) {
            return "Server '$name' has a url without a host: $url"
        }
        return null
    }

    private fun redactedOrigin(url: String): String {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return ""
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" } ?: ""
        return "${uri.scheme}://${uri.host}$port"
    }

    /** Canonical digest over the full candidates (header values included). */
    private fun candidateDigest(candidates: List<McpImportCandidate>): String {
        val canonical = buildJsonObject {
            put("mcpServers", buildJsonObject {
                candidates.forEach { candidate ->
                    put(candidate.serverName, buildJsonObject {
                        put(
                            "type",
                            if (candidate.transport == McpImportTransport.SSE) "sse" else "streamable_http",
                        )
                        put("url", candidate.url)
                        if (candidate.headers.isNotEmpty()) {
                            put("headers", buildJsonObject {
                                candidate.headers.forEach { (key, value) -> put(key, value) }
                            })
                        }
                    })
                }
            })
        }
        return sha256Hex(canonical.toString())
    }
}

/**
 * Detects duplicated keys at the top level of the `mcpServers` object.
 *
 * kotlinx.serialization collapses duplicate JSON object keys at parse time
 * (only the last entry survives), so duplicate detection must scan the raw
 * text — the P2-05 conflict check must not rely on the JSON-level collapse
 * and must not silently skip entries (test-fixtures/mcp/import README).
 */
internal fun detectDuplicateMcpServerNames(rawJson: String): List<String> {
    val keyStart = rawJson.indexOf("\"mcpServers\"")
    if (keyStart < 0) return emptyList()
    val colon = rawJson.indexOf(':', keyStart)
    if (colon < 0) return emptyList()
    val open = rawJson.indexOf('{', colon)
    if (open < 0) return emptyList()

    val seen = mutableMapOf<String, Int>()
    val duplicates = mutableListOf<String>()
    var depth = 1
    var i = open + 1
    while (i < rawJson.length && depth > 0) {
        val c = rawJson[i]
        when {
            c == '{' || c == '[' -> {
                depth++
                i++
            }
            c == '}' || c == ']' -> {
                depth--
                i++
            }
            c == '"' && depth == 1 -> {
                val token = readJsonStringToken(rawJson, i) ?: return duplicates
                // Advance by the RAW (escaped) length — the unescaped value
                // can be shorter than the raw text (e.g. \uXXXX escapes), and
                // using it would land the cursor inside the string and
                // silently skip later keys.
                var j = i + token.rawLength + 2
                while (j < rawJson.length && rawJson[j].isWhitespace()) j++
                if (j < rawJson.length && rawJson[j] == ':') {
                    val count = (seen[token.value] ?: 0) + 1
                    seen[token.value] = count
                    if (count == 2) duplicates += token.value
                }
                i = j
            }
            else -> i++
        }
    }
    return duplicates
}

/** Result of reading a JSON string token: the collapsed value used for
 *  comparison, and the raw (still escaped) character count consumed between
 *  the quotes used for cursor advancement. */
private data class JsonStringToken(val value: String, val rawLength: Int)

/** Reads a JSON string starting at `start` (the opening quote). */
private fun readJsonStringToken(raw: String, start: Int): JsonStringToken? {
    val sb = StringBuilder()
    var i = start + 1
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\') {
            if (i + 1 >= raw.length) return null
            sb.append(raw[i + 1])
            i += 2
        } else if (c == '"') {
            return JsonStringToken(value = sb.toString(), rawLength = i - start - 1)
        } else {
            sb.append(c)
            i++
        }
    }
    return null
}

/**
 * Parses the `mcpServers` object of a skill-embedded mcp.json into server
 * configs (shared by the skill MCP import paths).
 */
internal fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return mcpServers.entries.mapNotNull { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyList()
        val commonOptions = McpCommonOptions(name = name, headers = headers)
        when (type) {
            "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}
