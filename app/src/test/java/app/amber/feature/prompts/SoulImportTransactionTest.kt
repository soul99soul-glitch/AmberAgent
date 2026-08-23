package app.amber.feature.prompts

import android.app.Application
import android.content.Context
import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.ContentDigest
import app.amber.feature.tools.Capability
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P2-07 soul import, preview and rollback (parity plan §P2-07).
 *
 * Acceptance covered:
 *  - candidate prepared from a source label with diff, impact scope and
 *    base/candidate digests (bound digest);
 *  - blank / oversized candidates rejected;
 *  - CAS apply: a concurrent soul change (or changed candidate) rejects the
 *    stale approval inside the DataStore edit — never auto-overwrite;
 *  - previous soul kept for exactly one explicit rollback;
 *  - audit entries carry digests + runId + SOUL_UPDATE capability, never
 *    the content itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SoulImportTransactionTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var ledger: CasTestFixtures.FakeCasLedger
    private lateinit var transaction: SoulImportTransaction
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val soulV1 = "# Soul v1\nBe concise and helpful."
    private val soulV2 = "# Soul v2\nBe concise, helpful and always answer in the user's language."

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "soul-import-${System.nanoTime()}").apply { mkdirs() }
        settingsStore = CasTestFixtures.settingsAggregator(context, testRoot)
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        ledger = CasTestFixtures.FakeCasLedger()
        transaction = SoulImportTransaction(
            settingsStore = settingsStore,
            ledger = ledger,
            previousStore = SoulPreviousStore(context),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        testRoot.deleteRecursively()
    }

    private fun ready(prep: SoulImportTransaction.Preparation): SoulImportTransaction.Preparation.Ready {
        assertTrue("expected Ready but was ${prep.javaClass.simpleName}", prep is SoulImportTransaction.Preparation.Ready)
        return prep as SoulImportTransaction.Preparation.Ready
    }

    private fun rejected(prep: SoulImportTransaction.Preparation): SoulImportTransaction.Preparation.Rejected {
        assertTrue("expected Rejected but was ${prep.javaClass.simpleName}", prep is SoulImportTransaction.Preparation.Rejected)
        return prep as SoulImportTransaction.Preparation.Rejected
    }

    private suspend fun currentSoul(): String = settingsStore.settingsFlow.value.agentRuntime.agentSoulMarkdown

    // ---- preview ----

    @Test
    fun `preview shows diff impact scope and bound digest`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }

        val prep = ready(transaction.prepare("SOUL.md", soulV2))
        val preview = prep.preview
        assertEquals("SOUL.md", preview.source)
        assertEquals("high", preview.risk)
        assertTrue(preview.impactScope.contains("every conversation"))
        assertTrue(preview.diff.contains("-Be concise and helpful."))
        assertTrue(preview.diff.contains("+Be concise, helpful and always answer in the user's language."))
        assertEquals(ContentDigest.sha256(soulV1), preview.baseDigest)
        assertEquals(ContentDigest.sha256(soulV2), preview.candidateDigest)
        assertEquals(
            ContentDigest.bind(preview.baseDigest, preview.candidateDigest),
            preview.digest,
        )
    }

    @Test
    fun `preview rejects blank and oversized candidates`() = runBlocking {
        assertTrue(rejected(transaction.prepare("SOUL.md", "   ")).errors.any { it.contains("empty") })
        assertTrue(
            rejected(transaction.prepare("SOUL.md", "x".repeat(SoulImportTransaction.MAX_SOUL_CHARS + 1)))
                .errors.any { it.contains("limit") }
        )
    }

    // ---- apply (CAS) ----

    @Test
    fun `apply updates the soul atomically and keeps the previous version`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }
        val preview = ready(transaction.prepare("SOUL.md", soulV2)).preview

        val applied = transaction.apply("session-1", "run-42", preview.digest, soulV2)
        assertTrue("expected Applied but was ${applied.javaClass.simpleName}", applied is SoulImportTransaction.ApplyResult.Applied)
        assertEquals(soulV2, currentSoul())

        // Previous kept for rollback.
        val stored = SoulPreviousStore(context).load()
        assertTrue(stored != null)
        assertEquals(soulV1, stored!!.markdown)

        // Audit: SOUL_UPDATE capability, digests + runId, no content stored.
        val audit = ledger.entries.last()
        assertEquals(Capability.SOUL_UPDATE, audit.capability)
        assertEquals("applied", audit.outcome)
        assertEquals("run-42", audit.runId)
        assertEquals(preview.baseDigest, audit.oldDigest)
        assertEquals(preview.candidateDigest, audit.newDigest)
        assertTrue(ledger.entries.none { it.argsDigest.contains(soulV2) })
    }

    @Test
    fun `apply rejects the stale approval when the soul changed after the preview`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }
        val preview = ready(transaction.prepare("SOUL.md", soulV2)).preview

        // Another writer (or a concurrent edit) changes the soul after preview.
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = "# Intervening edit")) }

        val result = transaction.apply("session-1", "run-42", preview.digest, soulV2)
        assertTrue("expected Stale but was ${result.javaClass.simpleName}", result is SoulImportTransaction.ApplyResult.Stale)
        // Nothing was overwritten.
        assertEquals("# Intervening edit", currentSoul())
    }

    @Test
    fun `apply rejects when the candidate changed after the preview`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }
        val preview = ready(transaction.prepare("SOUL.md", soulV2)).preview

        val result = transaction.apply("session-1", "run-42", preview.digest, "# Different candidate")
        assertTrue("expected Stale but was ${result.javaClass.simpleName}", result is SoulImportTransaction.ApplyResult.Stale)
        assertEquals(soulV1, currentSoul())
    }

    // ---- rollback ----

    @Test
    fun `rollback restores the previous soul exactly once`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }
        val preview = ready(transaction.prepare("SOUL.md", soulV2)).preview
        transaction.apply("session-1", "run-1", preview.digest, soulV2)
        assertEquals(soulV2, currentSoul())

        val rolledBack = transaction.rollback("session-2", "run-2")
        assertTrue("expected RolledBack but was ${rolledBack.javaClass.simpleName}", rolledBack is SoulImportTransaction.RollbackResult.RolledBack)
        assertEquals(soulV1, currentSoul())

        // One-time rollback: the previous snapshot is gone.
        val second = transaction.rollback("session-3", "run-3")
        assertTrue("expected NoPrevious but was ${second.javaClass.simpleName}", second is SoulImportTransaction.RollbackResult.NoPrevious)

        val audit = ledger.entries.last()
        assertEquals("rolled_back", audit.outcome)
        assertEquals(preview.candidateDigest, audit.oldDigest)
        assertEquals(preview.baseDigest, audit.newDigest)
    }

    @Test
    fun `rollback refuses when the soul changed again after the import`() = runBlocking {
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = soulV1)) }
        val preview = ready(transaction.prepare("SOUL.md", soulV2)).preview
        transaction.apply("session-1", "run-1", preview.digest, soulV2)

        // The soul was changed again after the import — rollback must not
        // overwrite the newer version.
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(agentSoulMarkdown = "# Newer soul")) }
        val result = transaction.rollback("session-2", "run-2")
        assertTrue("expected Stale but was ${result.javaClass.simpleName}", result is SoulImportTransaction.RollbackResult.Stale)
        assertEquals("# Newer soul", currentSoul())
    }

    @Test
    fun `rollback refuses when no previous version exists`() = runBlocking {
        val result = transaction.rollback("session-1", "run-1")
        assertTrue(result is SoulImportTransaction.RollbackResult.NoPrevious)
    }
}
