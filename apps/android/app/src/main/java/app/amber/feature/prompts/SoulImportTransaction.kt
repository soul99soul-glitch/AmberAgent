package app.amber.feature.prompts

import android.content.Context
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.utils.JsonInstant
import app.amber.core.files.SimpleDiff
import app.amber.feature.runtime.CasAudit
import app.amber.feature.runtime.CasLedger
import app.amber.feature.runtime.ContentDigest
import app.amber.feature.tools.Capability
import java.io.File

/**
 * P2-07 soul (agent prompt config) import, preview and rollback
 * (docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md
 * §P2-07).
 *
 * Android already has manual soul editing (SettingAgentMemoryPage) and
 * runtime injection (agentSoulMarkdown → buildAgentSoulPrompt). This closes
 * only the agent-driven safe-update loop:
 *
 * 1. Candidate is prepared from workspace/SOUL.md (or an explicit workspace
 *    file) — validation, diff, source and impact scope.
 * 2. The approval binds base digest (currently applied soul) + candidate
 *    digest via [ContentDigest.bind].
 * 3. Apply re-reads the settings store and CAS-writes: any concurrent soul
 *    change aborts the DataStore edit and the approval is rejected as stale.
 * 4. The replaced soul is kept for exactly one explicit rollback.
 * 5. Audit entries (digests + outcome, never content) go to the P2-01
 *    approval history.
 *
 * Boundaries (plan §P2-07): no general self-evolution framework; tools never
 * bypass the existing manual-edit permission path; a soul update never
 * touches skills, MCP configs or recipes.
 */
class SoulImportTransaction(
    private val settingsStore: SettingsAggregator,
    private val ledger: CasLedger?,
    private val previousStore: SoulPreviousStore,
) {

    companion object {
        /** Hard cap for the soul markdown (it is injected into every conversation). */
        const val MAX_SOUL_CHARS = 200_000

        /** Default candidate file inside the workspace. */
        const val DEFAULT_SOUL_FILE = "SOUL.md"
    }

    data class SoulPreview(
        val source: String,
        val charCount: Int,
        val baseDigest: String,
        val candidateDigest: String,
        /** Approval key: bind(baseDigest, candidateDigest). */
        val digest: String,
        val diff: String,
        /** Impact scope description shown to the user. */
        val impactScope: String,
        /** Always "high": soul.update has a High risk floor (Capability.SOUL_UPDATE). */
        val risk: String,
    )

    sealed class Preparation {
        data class Ready(val preview: SoulPreview, val candidate: String) : Preparation()

        data class Rejected(val errors: List<String>) : Preparation()
    }

    sealed class ApplyResult {
        data class Applied(val charCount: Int) : ApplyResult()

        /** Base or candidate changed since the preview — approval is stale. */
        data class Stale(val reason: String) : ApplyResult()

        data class Rejected(val errors: List<String>) : ApplyResult()
    }

    sealed class RollbackResult {
        data class RolledBack(val charCount: Int) : RollbackResult()

        data class NoPrevious(val reason: String) : RollbackResult()

        data class Stale(val reason: String) : RollbackResult()
    }

    /** Current applied soul markdown (base for the next update). */
    suspend fun currentSoul(): String = settingsStore.settingsFlow.value.agentRuntime.agentSoulMarkdown

    /**
     * Stage 1: validation + diff + digests.
     *
     * @param source human-readable source label (workspace path).
     * @param soulMarkdown candidate content.
     */
    suspend fun prepare(source: String, soulMarkdown: String): Preparation {
        if (soulMarkdown.isBlank()) {
            return Preparation.Rejected(listOf("The soul candidate is empty"))
        }
        if (soulMarkdown.length > MAX_SOUL_CHARS) {
            return Preparation.Rejected(
                listOf("The soul candidate exceeds the $MAX_SOUL_CHARS char limit (it is injected into every conversation)")
            )
        }
        val base = currentSoul()
        val baseDigest = ContentDigest.sha256(base)
        val candidateDigest = ContentDigest.sha256(soulMarkdown)
        return Preparation.Ready(
            preview = SoulPreview(
                source = source,
                charCount = soulMarkdown.length,
                baseDigest = baseDigest,
                candidateDigest = candidateDigest,
                digest = ContentDigest.bind(baseDigest, candidateDigest),
                diff = SimpleDiff.unifiedDiff(base, soulMarkdown, fileLabel = "SOUL.md"),
                impactScope = "App-level behavior guide (agentSoulMarkdown) injected into every conversation",
                risk = "high",
            ),
            candidate = soulMarkdown,
        )
    }

    /**
     * Stage 2: CAS apply. Re-reads the current soul inside the DataStore edit;
     * a concurrent change aborts the edit and rejects the stale approval.
     * The replaced soul is kept for one rollback.
     */
    suspend fun apply(sessionId: String, runId: String?, expectedDigest: String, candidate: String): ApplyResult {
        val preparation = prepare("candidate", candidate)
        if (preparation is Preparation.Rejected) return ApplyResult.Rejected(preparation.errors)
        val ready = preparation as Preparation.Ready

        val currentDigest = ContentDigest.sha256(currentSoul())
        if (ContentDigest.bind(currentDigest, ready.preview.candidateDigest) != expectedDigest) {
            return ApplyResult.Stale(
                "The soul (or the candidate) changed after the preview; re-run soul_preview and approve the new digest."
            )
        }
        val previous = currentSoul()
        val applied = writeSoulWithCas(candidate, expectedCurrentDigest = currentDigest)
        if (!applied) {
            return ApplyResult.Stale(
                "The soul changed concurrently while applying; the update was NOT applied. Re-run soul_preview and approve the new digest."
            )
        }
        previousStore.save(
            SoulPreviousStore.SoulPrevious(
                markdown = previous,
                replacedDigest = currentDigest,
                candidateDigest = ready.preview.candidateDigest,
                savedAtMs = System.currentTimeMillis(),
            )
        )
        ledger?.recordApproval(
            CasAudit.outcome(
                capability = Capability.SOUL_UPDATE,
                toolName = "soul_import",
                sessionId = sessionId,
                runId = runId,
                digest = expectedDigest,
                source = "user",
                outcome = "applied",
                oldDigest = currentDigest,
                newDigest = ready.preview.candidateDigest,
            )
        )
        return ApplyResult.Applied(charCount = ready.preview.charCount)
    }

    /**
     * Stage 3: one explicit rollback. Restores the kept previous soul and
     * deletes it — a second rollback is impossible. CAS: the current soul
     * must still be the promoted candidate.
     */
    suspend fun rollback(sessionId: String, runId: String?): RollbackResult {
        val previous = previousStore.load()
            ?: return RollbackResult.NoPrevious("No previous soul version to roll back to")
        val currentDigest = ContentDigest.sha256(currentSoul())
        if (currentDigest != previous.candidateDigest) {
            return RollbackResult.Stale(
                "The soul changed again after the import; a rollback would overwrite the newer version."
            )
        }
        val applied = writeSoulWithCas(previous.markdown, expectedCurrentDigest = currentDigest)
        if (!applied) {
            return RollbackResult.Stale("The soul changed concurrently while rolling back; nothing was overwritten.")
        }
        previousStore.clear()
        ledger?.recordApproval(
            CasAudit.outcome(
                capability = Capability.SOUL_UPDATE,
                toolName = "soul_rollback",
                sessionId = sessionId,
                runId = runId,
                digest = previous.candidateDigest,
                source = "user",
                outcome = "rolled_back",
                oldDigest = previous.candidateDigest,
                newDigest = previous.replacedDigest,
            )
        )
        return RollbackResult.RolledBack(charCount = previous.markdown.length)
    }

    /**
     * DataStore-level CAS: the edit transform re-verifies the expected base
     * digest and throws to abort the transaction when a concurrent writer
     * changed the soul in between. Returns true only when the write landed.
     */
    private suspend fun writeSoulWithCas(candidate: String, expectedCurrentDigest: String): Boolean {
        return try {
            settingsStore.update { settings ->
                val current = settings.agentRuntime.agentSoulMarkdown
                if (ContentDigest.sha256(current) != expectedCurrentDigest) {
                    throw SoulCasAbortException()
                }
                settings.copy(
                    agentRuntime = settings.agentRuntime.copy(agentSoulMarkdown = candidate)
                )
            }
            true
        } catch (e: SoulCasAbortException) {
            false
        }
    }

    private class SoulCasAbortException : IllegalStateException()
}

/**
 * File-backed store for the single previous soul version (digest + markdown
 * kept app-private for the one-time rollback). Lives outside the settings
 * aggregate so the soul itself stays the only source of truth in settings.
 */
class SoulPreviousStore(private val context: Context) {
    @kotlinx.serialization.Serializable
    data class SoulPrevious(
        val markdown: String,
        val replacedDigest: String,
        val candidateDigest: String,
        val savedAtMs: Long,
    )

    /** Meta written to meta.json — never embeds the markdown (n1): the
     *  content is read/written only from [markdownFile]. */
    @kotlinx.serialization.Serializable
    private data class SoulPreviousMeta(
        val replacedDigest: String,
        val candidateDigest: String,
        val savedAtMs: Long,
    )

    private val directory: File
        get() = File(context.filesDir, "soul_previous").apply { mkdirs() }

    private val markdownFile: File get() = File(directory, "previous.md")
    private val metaFile: File get() = File(directory, "meta.json")

    fun save(previous: SoulPrevious) {
        markdownFile.writeText(previous.markdown)
        metaFile.writeText(
            JsonInstant.encodeToString(
                SoulPreviousMeta.serializer(),
                SoulPreviousMeta(
                    replacedDigest = previous.replacedDigest,
                    candidateDigest = previous.candidateDigest,
                    savedAtMs = previous.savedAtMs,
                ),
            )
        )
    }

    fun load(): SoulPrevious? {
        if (!markdownFile.exists() || !metaFile.exists()) return null
        val meta = runCatching {
            JsonInstant.decodeFromString(SoulPreviousMeta.serializer(), metaFile.readText())
        }.getOrNull() ?: return null
        return SoulPrevious(
            markdown = markdownFile.readText(),
            replacedDigest = meta.replacedDigest,
            candidateDigest = meta.candidateDigest,
            savedAtMs = meta.savedAtMs,
        )
    }

    fun clear() {
        markdownFile.delete()
        metaFile.delete()
    }
}
