package app.amber.core.ai.mcp

import app.amber.feature.runtime.ApprovalHistoryEntry
import app.amber.feature.runtime.CasLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-05 MCP import safety transaction (parity plan §P2-05), driven by the
 * six versioned fixtures under test-fixtures/mcp/import/:
 *
 * http / sse → normal preview; unknown transport → fail closed; missing url
 * → explicit error; duplicate names → whole-batch reject; headers → redacted
 * preview (names only, values never leak).
 */
class McpImportTransactionTest {

    private fun fixture(name: String): String {
        val stream = requireNotNull(javaClass.classLoader) { "Missing test resource" }
            .getResourceAsStream("mcp/import/$name.json")
        return requireNotNull(stream) { "Missing test resource: mcp/import/$name.json" }
            .use { it.readBytes().decodeToString() }
    }

    private class FakePreflight(
        private val toolNames: List<String> = listOf("tool_a", "tool_b"),
        private val failServer: String? = null,
    ) : McpConnectPreflight {
        val connected = mutableListOf<McpImportCandidate>()

        override suspend fun connectAndListTools(server: McpImportCandidate): List<String> {
            connected += server
            if (server.serverName == failServer) error("connection refused for ${server.serverName}")
            return toolNames
        }
    }

    private class FakeLedger : CasLedger {
        val entries = mutableListOf<ApprovalHistoryEntry>()

        override suspend fun recordApproval(entry: ApprovalHistoryEntry) {
            entries += entry
        }

        override suspend fun approvedDigest(sessionId: String): String? =
            entries.lastOrNull { it.toolCallId == sessionId }
                ?.takeIf { it.decision == "approved" }
                ?.argsDigest

        override suspend fun recordOutcome(sessionId: String, outcome: String) {
            val latest = entries.lastOrNull { it.toolCallId == sessionId } ?: return
            entries += latest.copy(
                id = "outcome-${entries.size}",
                decision = if (outcome == "rejected") "denied" else latest.decision,
                outcome = outcome,
            )
        }
    }

    private fun transaction(
        preflight: McpConnectPreflight = FakePreflight(),
        ledger: CasLedger = FakeLedger(),
        existing: Set<String> = emptySet(),
        published: MutableList<List<McpServerConfig>> = mutableListOf(),
    ) = McpImportTransaction(
        preflight = preflight,
        approvalLedger = ledger,
        existingServerNames = { existing },
        publish = { configs -> published += configs },
    )

    private fun prepare(
        transaction: McpImportTransaction,
        raw: String,
    ): McpImportPreparation = transaction.prepare(raw)

    private fun ready(prep: McpImportPreparation): McpImportPreparation.Ready {
        assertTrue("expected Ready but was ${prep.javaClass.simpleName}", prep is McpImportPreparation.Ready)
        return prep as McpImportPreparation.Ready
    }

    private fun rejected(prep: McpImportPreparation): McpImportPreparation.Rejected {
        assertTrue("expected Rejected but was ${prep.javaClass.simpleName}", prep is McpImportPreparation.Rejected)
        return prep as McpImportPreparation.Rejected
    }

    // ---- fixtures: prepare ----

    @Test
    fun httpFixturePreparesHttpTransportPreview() {
        val prep = ready(prepare(transaction(), fixture("http")))

        assertEquals(1, prep.preview.serverCount)
        val server = prep.preview.servers.single()
        assertEquals("fetch", server.serverName)
        assertEquals(McpImportTransport.STREAMABLE_HTTP, server.transport)
        assertEquals("https://mcp.example.com", server.origin)
        assertEquals("normal", server.risk)
        assertTrue(server.headerNames.isEmpty())
        assertEquals(64, prep.preview.digest.length)
    }

    @Test
    fun sseFixturePreparesSseTransportPreview() {
        val prep = ready(prepare(transaction(), fixture("sse")))

        val server = prep.preview.servers.single()
        assertEquals("events", server.serverName)
        assertEquals(McpImportTransport.SSE, server.transport)
        assertEquals("https://mcp.example.com", server.origin)
    }

    @Test
    fun unknownTransportFailsClosed() {
        val prep = rejected(prepare(transaction(), fixture("unknown-transport")))

        assertTrue(
            "must report the unsupported transport (stdio): ${prep.errors}",
            prep.errors.any { it.contains("stdio") },
        )
        assertTrue(prep.errors.any { it.contains("fail closed") })
    }

    @Test
    fun missingUrlIsAnExplicitErrorNotASilentSkip() {
        val prep = rejected(prepare(transaction(), fixture("missing-url")))

        assertTrue(
            "must explicitly report the missing url: ${prep.errors}",
            prep.errors.any { it.contains("url") && it.contains("broken") },
        )
    }

    @Test
    fun duplicateNamesRejectTheWholeBatch() {
        val prep = rejected(prepare(transaction(), fixture("duplicate-names")))

        assertTrue(
            "duplicate names must reject the batch: ${prep.errors}",
            prep.errors.any { it.contains("Duplicate") && it.contains("notes") },
        )
    }

    @Test
    fun duplicateNamesWithEscapedUnicodeKeysAreStillDetected() {
        // The keys contain JSON \uXXXX escapes: the raw token is longer than
        // its unescaped value, so the scanner cursor must advance by the raw
        // length or the second occurrence is silently skipped.
        val raw = """
            {
              "mcpServers": {
                "s\u0065rver": { "type": "streamable_http", "url": "https://a.example.com" },
                "other": { "type": "streamable_http", "url": "https://b.example.com" },
                "s\u0065rver": { "type": "streamable_http", "url": "https://c.example.com" }
              }
            }
        """.trimIndent()

        val duplicates = detectDuplicateMcpServerNames(raw)
        assertTrue(
            "escaped duplicate key must be detected: $duplicates",
            duplicates.contains("su0065rver"),
        )
        val prep = rejected(prepare(transaction(), raw))
        assertTrue(
            "escaped duplicate names must reject the batch: ${prep.errors}",
            prep.errors.any { it.contains("Duplicate") && it.contains("su0065rver") },
        )
    }

    @Test
    fun headersAreRedactedToNamesOnly() {
        val prep = ready(prepare(transaction(), fixture("with-headers")))

        val server = prep.preview.servers.single()
        assertEquals(listOf("Authorization", "X-Custom-Header"), server.headerNames)
        assertEquals("high", server.risk)
        // No header value ever leaks into the preview or the audit digest.
        val serialized = prep.preview.toString()
        assertFalse(serialized.contains("placeholder-token"))
        assertFalse(serialized.contains("custom-value"))
    }

    @Test
    fun headerValuesArePartOfTheBindingDigest() {
        val transaction = transaction()
        val prep = ready(prepare(transaction, fixture("with-headers")))
        val withoutValue = fixture("with-headers").replace("placeholder-token", "other-token")

        assertTrue("changed header value must change the digest", prep.preview.digest !=
            ready(prepare(transaction, withoutValue)).preview.digest)
    }

    // ---- apply: approval binding ----

    @Test
    fun applyWithoutApprovalIsStale() = runBlocking {
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(ledger = ledger, published = published)

        val result = transaction.apply(fixture("http"), sessionId = "s1")

        assertTrue("must be stale without approval: $result", result is McpImportApplyResult.Stale)
        assertTrue(published.isEmpty())
    }

    @Test
    fun applyAfterApprovalPublishesWholeBatchOnce() = runBlocking {
        val preflight = FakePreflight()
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(preflight = preflight, ledger = ledger, published = published)

        val raw = fixture("http")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        val result = transaction.apply(raw, sessionId = "s1")

        assertTrue("expected Applied but was $result", result is McpImportApplyResult.Applied)
        val applied = result as McpImportApplyResult.Applied
        assertEquals(1, applied.serverCount)
        assertEquals(2, applied.toolCount)
        assertEquals(1, published.size)
        assertEquals(1, published.single().size)
        assertEquals("fetch", published.single().single().commonOptions.name)
        assertEquals(1, preflight.connected.size)
        // Audit trail: approval (pending) + outcome (applied), digest only, no plaintext.
        assertEquals("approved", ledger.entries.first().decision)
        assertEquals("applied", ledger.entries.last().outcome)
        assertEquals(1, ledger.entries.first().serverCount)
        assertEquals("normal", ledger.entries.first().riskLabel)
    }

    @Test
    fun changedInputAfterApprovalIsStaleAndNothingIsPublished() = runBlocking {
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(ledger = ledger, published = published)

        val raw = fixture("http")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        // The file changed between preview and apply.
        val mutated = raw.replace("https://mcp.example.com/fetch", "https://mcp.example.com/changed")
        val result = transaction.apply(mutated, sessionId = "s1")

        assertTrue("must return stale preview: $result", result is McpImportApplyResult.Stale)
        assertTrue(published.isEmpty())
    }

    @Test
    fun approvalForDifferentCandidateDigestIsStaleAndNothingIsPublished() = runBlocking {
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(ledger = ledger, published = published)

        val raw = fixture("http")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview.copy(digest = "different-candidate"), sessionId = "s1")

        val result = transaction.apply(raw, sessionId = "s1")

        assertTrue("must reject an approval for a different digest: $result", result is McpImportApplyResult.Stale)
        assertTrue(published.isEmpty())
    }

    // ---- apply: whole-batch semantics ----

    @Test
    fun anyPreflightFailureRejectsTheWholeBatch() = runBlocking {
        val raw = """
            {
              "mcpServers": {
                "good": { "type": "streamable_http", "url": "https://mcp.example.com/good" },
                "bad": { "type": "sse", "url": "https://mcp.example.com/bad" }
              }
            }
        """.trimIndent()
        val preflight = FakePreflight(failServer = "bad")
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(preflight = preflight, ledger = ledger, published = published)

        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        val result = transaction.apply(raw, sessionId = "s1")

        assertTrue("must reject the whole batch: $result", result is McpImportApplyResult.Rejected)
        assertTrue((result as McpImportApplyResult.Rejected).errors.any { it.contains("bad") })
        assertTrue("nothing must be published on partial failure", published.isEmpty())
        assertEquals("rejected", ledger.entries.last().outcome)
    }

    @Test
    fun existingServerNameConflictsRejectTheWholeBatch() = runBlocking {
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(
            ledger = ledger,
            existing = setOf("fetch"),
            published = published,
        )

        val raw = fixture("http")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        val result = transaction.apply(raw, sessionId = "s1")

        assertTrue("existing name must reject: $result", result is McpImportApplyResult.Rejected)
        assertTrue((result as McpImportApplyResult.Rejected).errors.any { it.contains("already exists") })
        assertTrue(published.isEmpty())
        assertEquals("rejected", ledger.entries.last().outcome)
    }

    // ---- n3: a rejected outcome must invalidate the approval ----

    @Test
    fun applyAfterRejectedOutcomeIsStaleEvenWithTheSameDigest() = runBlocking {
        val preflight = FakePreflight(failServer = "fetch")
        val ledger = FakeLedger()
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(preflight = preflight, ledger = ledger, published = published)

        val raw = fixture("http")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        val first = transaction.apply(raw, sessionId = "s1")
        assertTrue("first apply must reject: $first", first is McpImportApplyResult.Rejected)
        assertEquals("rejected", ledger.entries.last().outcome)

        // Same file, same digest: the rejected outcome replaces the approval
        // decision, so the same digest must not be re-appliable.
        val second = transaction.apply(raw, sessionId = "s1")
        assertTrue("expected Stale after rejection but was $second", second is McpImportApplyResult.Stale)
        assertTrue(published.isEmpty())
    }

    // ---- candidate → config mapping ----

    @Test
    fun appliedCandidatesBecomeTypedServerConfigs() = runBlocking {
        val published = mutableListOf<List<McpServerConfig>>()
        val transaction = transaction(published = published)

        val raw = fixture("sse")
        val prep = ready(prepare(transaction, raw))
        transaction.approve(prep.preview, sessionId = "s1")
        transaction.apply(raw, sessionId = "s1")

        val config = published.single().single()
        assertTrue(config is McpServerConfig.SseTransportServer)
        assertEquals("events", config.commonOptions.name)
        assertEquals("https://mcp.example.com/events", (config as McpServerConfig.SseTransportServer).url)
    }
}
