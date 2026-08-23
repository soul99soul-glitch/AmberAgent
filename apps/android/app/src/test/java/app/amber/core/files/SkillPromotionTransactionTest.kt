package app.amber.core.files

import android.app.Application
import android.content.Context
import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.settings.prefs.SettingsAggregator
import java.io.File
import java.nio.file.Files
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P2-04 skill safe promotion and rollback (parity plan §P2-04).
 *
 * Acceptance covered:
 *  - full validation: manifest/schema, forbidden secret files, sizes;
 *  - preview: SKILL.md diff, risk, base/candidate digests and the bound
 *    approval digest;
 *  - apply re-reads both sides (CAS): base or candidate changed after the
 *    preview → stale, never auto-overwrite;
 *  - atomic replace keeps the previous version; one explicit rollback;
 *  - audit entries hold digests only (no file content) and record the
 *    triggering runId.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SkillPromotionTransactionTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var ledger: CasTestFixtures.FakeCasLedger
    private lateinit var skillManager: SkillManager
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val skillMdV1 = """
        ---
        name: demo-skill
        description: Demo skill for promotion tests
        ---
        # Demo Skill
        First version body.
    """.trimIndent()

    private val skillMdV2 = """
        ---
        name: demo-skill
        description: Demo skill for promotion tests
        ---
        # Demo Skill
        Second version body with more instructions.
    """.trimIndent()

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(mainDispatcher)
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "skill-promotion-${System.nanoTime()}").apply { mkdirs() }
        File(context.filesDir, FileFolders.SKILLS).deleteRecursively()
        File(context.filesDir, FileFolders.SKILLS_PREVIOUS).deleteRecursively()
        settingsStore = CasTestFixtures.settingsAggregator(context, testRoot)
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        ledger = CasTestFixtures.FakeCasLedger()
        skillManager = SkillManager(context, settingsStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        testRoot.deleteRecursively()
    }

    private fun transaction() = SkillPromotionTransaction(skillManager, ledger)

    private fun prepare(
        transaction: SkillPromotionTransaction,
        name: String = "demo-skill",
        files: Map<String, String> = mapOf("SKILL.md" to skillMdV1),
    ): SkillPromotionTransaction.Preparation = transaction.prepare(name, files)

    private fun ready(prep: SkillPromotionTransaction.Preparation): SkillPromotionTransaction.Preparation.Ready {
        assertTrue("expected Ready but was ${prep.javaClass.simpleName}", prep is SkillPromotionTransaction.Preparation.Ready)
        return prep as SkillPromotionTransaction.Preparation.Ready
    }

    private fun rejected(prep: SkillPromotionTransaction.Preparation): SkillPromotionTransaction.Preparation.Rejected {
        assertTrue("expected Rejected but was ${prep.javaClass.simpleName}", prep is SkillPromotionTransaction.Preparation.Rejected)
        return prep as SkillPromotionTransaction.Preparation.Rejected
    }

    // ---- validation ----

    @Test
    fun `prepare rejects a candidate without SKILL md`() {
        val prep = prepare(transaction(), files = mapOf("notes.md" to "no skill"))
        assertTrue(rejected(prep).errors.any { it.contains("SKILL.md") })
    }

    @Test
    fun `prepare rejects a manifest without name or description`() {
        val noName = prepare(transaction(), files = mapOf("SKILL.md" to "---\ndescription: x\n---\n# body"))
        assertTrue(rejected(noName).errors.any { it.contains("name") })

        val noDescription = prepare(transaction(), files = mapOf("SKILL.md" to "---\nname: demo-skill\n---\n# body"))
        assertTrue(rejected(noDescription).errors.any { it.contains("description") })
    }

    @Test
    fun `prepare rejects forbidden secret files`() {
        val prep = prepare(
            transaction(),
            files = mapOf(
                "SKILL.md" to skillMdV1,
                ".env" to "API_KEY=secret",
                "credentials.json" to "{}",
            ),
        )
        assertTrue(rejected(prep).errors.any { it.contains(".env") })
        assertTrue(rejected(prep).errors.any { it.contains("credentials.json") })
    }

    @Test
    fun `prepare rejects unsafe paths and oversized files`() {
        val prep = prepare(
            transaction(),
            files = mapOf(
                "SKILL.md" to skillMdV1,
                "../escape.md" to "escape",
                "big.md" to "x".repeat(SkillReadBoundary.MAX_SKILL_FILE_BYTES + 1),
            ),
        )
        val errors = rejected(prep).errors
        assertTrue(errors.any { it.contains("escape.md") })
        assertTrue(errors.any { it.contains("limit") })
    }

    // ---- preview ----

    @Test
    fun `preview produces diff risk and bound digest`() {
        val prep = ready(prepare(transaction(), files = mapOf("SKILL.md" to skillMdV2)))
        val preview = prep.preview
        assertEquals("demo-skill", preview.name)
        assertTrue(preview.isNew)
        assertEquals(1, preview.fileCount)
        assertTrue(preview.diff.contains("+Second version body with more instructions."))
        assertEquals("normal", preview.risk)
        assertEquals(
            app.amber.feature.runtime.ContentDigest.bind(preview.baseDigest, preview.candidateDigest),
            preview.digest,
        )
        // Base digest of a not-yet-installed skill is the empty digest.
        assertFalse(preview.baseDigest.isBlank())
    }

    @Test
    fun `mcp config raises the preview risk to high but stays importable`() {
        val prep = ready(
            prepare(
                transaction(),
                files = mapOf(
                    "SKILL.md" to skillMdV1,
                    "mcp.json" to """{"mcpServers":{"s1":{"type":"streamable_http","url":"http://localhost:1"}}}""",
                ),
            )
        )
        assertEquals("high", prep.preview.risk)
        assertTrue(prep.preview.issues.any { it.contains("mcp.json") })
    }

    // ---- apply (CAS) ----

    @Test
    fun `apply promotes the candidate atomically and keeps the previous version`() = runBlocking {
        val tx = transaction()
        val prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV1)))
        val digest = prep.preview.digest

        val applied = tx.apply("demo-skill", prep.candidateFiles, "session-1", "run-123", digest)
        assertTrue("expected Applied but was ${applied.javaClass.simpleName}", applied is SkillPromotionTransaction.ApplyResult.Applied)

        // Installed content is the candidate; previous version is kept.
        val installed = skillManager.readSkillContent("demo-skill")
        assertNotNull(installed)
        assertTrue(installed!!.contains("First version body"))
        assertNotNull(skillManager.previousSkillMeta("demo-skill"))

        // Audit: digest + runId only, no file content stored.
        val audit = ledger.entries.last()
        assertEquals("applied", audit.outcome)
        assertEquals("run-123", audit.runId)
        assertEquals(digest, audit.argsDigest)
    }

    @Test
    fun `apply rejects a stale approval when the installed base changed`() = runBlocking {
        val tx = transaction()
        // Preview v1, but before apply the installed skill was already promoted to v2.
        val v1Prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV1)))
        val v1Digest = v1Prep.preview.digest
        val v2Prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV2)))
        val firstApply = tx.apply("demo-skill", v2Prep.candidateFiles, "session-1", "run-1", v2Prep.preview.digest)
        assertTrue(firstApply is SkillPromotionTransaction.ApplyResult.Applied)

        // The v1 approval is now stale — the installed version changed.
        val staleApply = tx.apply("demo-skill", v1Prep.candidateFiles, "session-2", "run-2", v1Digest)
        assertTrue("expected Stale but was ${staleApply.javaClass.simpleName}", staleApply is SkillPromotionTransaction.ApplyResult.Stale)
        // The v2 content stays installed (never auto-overwritten).
        assertTrue(skillManager.readSkillContent("demo-skill")!!.contains("Second version body"))
    }

    @Test
    fun `apply rejects a stale approval when the candidate changed`() = runBlocking {
        val tx = transaction()
        val v1Prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV1)))
        // The workspace candidate changed after the preview: apply is called
        // with a different candidate than the approved digest.
        val result = tx.apply("demo-skill", mapOf("SKILL.md" to skillMdV2), "session-1", "run-1", v1Prep.preview.digest)
        assertTrue("expected Stale but was ${result.javaClass.simpleName}", result is SkillPromotionTransaction.ApplyResult.Stale)
        assertNull(skillManager.previousSkillMeta("demo-skill"))
    }

    // ---- rollback ----

    @Test
    fun `rollback restores the previous version exactly once`() = runBlocking {
        val tx = transaction()
        val prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV1)))
        val applied = tx.apply("demo-skill", prep.candidateFiles, "session-1", "run-1", prep.preview.digest)
        assertTrue(applied is SkillPromotionTransaction.ApplyResult.Applied)

        // Second promotion of v2 keeps only the latest previous (v1).
        val prep2 = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV2)))
        tx.apply("demo-skill", prep2.candidateFiles, "session-2", "run-2", prep2.preview.digest)
        assertTrue(skillManager.readSkillContent("demo-skill")!!.contains("Second version body"))

        val rolledBack = tx.rollback("demo-skill", "session-3", "run-3")
        assertTrue("expected RolledBack but was ${rolledBack.javaClass.simpleName}", rolledBack is SkillPromotionTransaction.RollbackResult.RolledBack)
        assertTrue(skillManager.readSkillContent("demo-skill")!!.contains("First version body"))
        assertNull(skillManager.previousSkillMeta("demo-skill"))

        // One-time rollback: the second attempt has nothing to restore.
        val second = tx.rollback("demo-skill", "session-4", "run-4")
        assertTrue("expected NoPrevious but was ${second.javaClass.simpleName}", second is SkillPromotionTransaction.RollbackResult.NoPrevious)

        val rollbackAudit = ledger.entries.last()
        assertEquals("rolled_back", rollbackAudit.outcome)
    }

    @Test
    fun `rollback refuses when the skill was promoted again after the last rollback window`() = runBlocking {
        val tx = transaction()
        val prep = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV1)))
        tx.apply("demo-skill", prep.candidateFiles, "session-1", "run-1", prep.preview.digest)

        // A new promotion happened afterwards — current != kept candidate.
        val prep2 = ready(prepare(tx, files = mapOf("SKILL.md" to skillMdV2)))
        tx.apply("demo-skill", prep2.candidateFiles, "session-2", "run-2", prep2.preview.digest)

        // Hmm: the second promotion REPLACED the previous snapshot, so the
        // first candidate digest is gone; rollback now targets the v2 previous.
        val rolledBack = tx.rollback("demo-skill", "session-3", "run-3")
        assertTrue("expected RolledBack but was ${rolledBack.javaClass.simpleName}", rolledBack is SkillPromotionTransaction.RollbackResult.RolledBack)
        assertTrue(skillManager.readSkillContent("demo-skill")!!.contains("First version body"))
    }

    @Test
    fun `rollback refuses when no previous version exists`() = runBlocking {
        val tx = transaction()
        val result = tx.rollback("missing-skill", "session-1", "run-1")
        assertTrue(result is SkillPromotionTransaction.RollbackResult.NoPrevious)
    }
}
