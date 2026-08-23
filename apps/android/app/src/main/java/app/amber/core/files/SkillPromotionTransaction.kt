package app.amber.core.files

import app.amber.feature.runtime.CasAudit
import app.amber.feature.runtime.CasLedger
import app.amber.feature.runtime.ContentDigest
import app.amber.feature.tools.Capability
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.capabilityForTool
import java.io.File

/**
 * P2-04 skill safe promotion and rollback (docs/plans/2026-08-13-android-ios-
 * capability-parity-closure-plan.md §P2-04).
 *
 * Closed loop:
 *
 * 1. Candidate is prepared (staged in memory / validated) with a full parse.
 * 2. [prepare] validates manifest/schema, paths and file types, tool/capability
 *    references, MCP dependencies, prompt size and forbidden secret files; it
 *    produces the preview (file list, SKILL.md diff, risk, base digest of the
 *    currently installed version, candidate digest) and the bound digest
 *    [ContentDigest.bind] over base+candidate.
 * 3. The user approves a call that carries the bound digest (the approval is
 *    bound to base+candidate digest through the args-digest approval guard).
 * 4. [apply] re-reads the workspace candidate AND the currently installed
 *    version, recomputes the bound digest and rejects it as stale when either
 *    side changed since the preview (CAS).
 * 5. The replace is atomic ([SkillManager.promoteSkill]); the replaced version
 *    is kept as previous for exactly one explicit rollback.
 * 6. [rollback] restores the previous version once (CAS against the promoted
 *    candidate digest) and records the audit outcome.
 *
 * Audit goes through the P2-01 approval history via [CasLedger] — digests and
 * outcome only, never file content. ZIP import stays supported: the candidate
 * is produced by the same unzip path as before (Android advantage preserved).
 */
class SkillPromotionTransaction(
    private val skillManager: SkillManager,
    private val ledger: CasLedger?,
) {

    /** Soft "large prompt" threshold for the risk/issues note. */
    private val largePromptChars = 200_000

    data class SkillPromotionPreview(
        val name: String,
        val isNew: Boolean,
        val fileCount: Int,
        val changedFiles: List<String>,
        val diff: String,
        val baseDigest: String,
        val candidateDigest: String,
        /** Approval key: bind(baseDigest, candidateDigest). */
        val digest: String,
        /** "high" | "normal". */
        val risk: String,
        val promptSizeChars: Int,
        val issues: List<String>,
    )

    sealed class Preparation {
        data class Ready(val preview: SkillPromotionPreview, val candidateFiles: Map<String, String>) : Preparation()

        data class Rejected(val errors: List<String>) : Preparation()
    }

    sealed class ApplyResult {
        data class Applied(val name: String, val fileCount: Int) : ApplyResult()

        /** Base or candidate changed since the preview — approval is stale. */
        data class Stale(val reason: String) : ApplyResult()

        data class Rejected(val errors: List<String>) : ApplyResult()
    }

    sealed class RollbackResult {
        data class RolledBack(val name: String) : RollbackResult()

        data class NoPrevious(val reason: String) : RollbackResult()

        data class Stale(val reason: String) : RollbackResult()
    }

    /**
     * Stage 1: full validation + preview + digests.
     *
     * @param name skill name from the candidate SKILL.md frontmatter.
     * @param candidateFiles candidate content (path → text), already read
     *   from the workspace folder / SKILL.md / zip.
     */
    fun prepare(name: String, candidateFiles: Map<String, String>): Preparation {
        val errors = mutableListOf<String>()

        val skillMd = candidateFiles["SKILL.md"]
        if (skillMd.isNullOrBlank()) {
            return Preparation.Rejected(listOf("Skill package does not contain SKILL.md"))
        }
        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val manifestName = frontmatter["name"]?.trim().orEmpty()
        if (manifestName.isBlank()) {
            errors += "SKILL.md manifest is missing name"
        }
        if (SkillFrontmatterParser.isPlaceholderDescription(frontmatter["description"])) {
            errors += "SKILL.md manifest is missing a valid description"
        }

        // Paths, file types, sizes, binary and secret files.
        for ((relativePath, content) in candidateFiles) {
            val rejection = SkillReadBoundary.checkCandidatePath(relativePath)
            if (rejection != null) {
                errors += "Candidate file '$relativePath' rejected: ${rejection.detail}"
                continue
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > SkillReadBoundary.MAX_SKILL_FILE_BYTES) {
                errors += "Candidate file '$relativePath' exceeds the ${SkillReadBoundary.MAX_SKILL_FILE_BYTES / (1024 * 1024)}MB limit"
            }
        }

        // Tool / capability references.
        val allowedTools = frontmatter["allowed-tools"]
            ?.split(Regex("""\s+"""))
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (allowedTools.isNotEmpty()) {
            val invalid = allowedTools.filter { it.contains(',') || it.startsWith("/") }
            if (invalid.isNotEmpty()) {
                errors += "allowed-tools contains invalid tool references: ${invalid.joinToString(", ")}"
            }
        }

        if (errors.isNotEmpty()) return Preparation.Rejected(errors)

        // MCP dependency: mcp.json is allowed to travel with the skill (the
        // MCP import capability governs it separately) but raises the risk.
        val containsMcpConfig = candidateFiles.keys.any { it.equals("mcp.json", ignoreCase = true) }

        val issues = mutableListOf<String>()
        val promptSizeChars = skillMd.length
        if (promptSizeChars > largePromptChars) {
            issues += "SKILL.md is large ($promptSizeChars chars); it will consume a significant share of the context window"
        }
        if (containsMcpConfig) {
            issues += "Candidate carries mcp.json — its MCP servers are imported separately under the mcp.import capability"
        }

        val highRiskToolRefs = allowedTools.mapNotNull { capabilityForTool(it) }
            .filter { it.riskFloor == ToolRisk.High || it.riskFloor == ToolRisk.Sensitive }
        val risk = if (containsMcpConfig || highRiskToolRefs.isNotEmpty()) "high" else "normal"

        val installed = readInstalledFiles(name)
        val isNew = installed.isEmpty()
        val baseDigest = filesDigest(installed)
        val candidateDigest = filesDigest(candidateFiles)
        val digest = ContentDigest.bind(baseDigest, candidateDigest)
        val changedFiles = changedPaths(installed, candidateFiles)
        val diff = SimpleDiff.unifiedDiff(
            installed["SKILL.md"].orEmpty(),
            candidateFiles["SKILL.md"].orEmpty(),
            fileLabel = "SKILL.md",
        )

        return Preparation.Ready(
            preview = SkillPromotionPreview(
                name = manifestName.ifBlank { name },
                isNew = isNew,
                fileCount = candidateFiles.size,
                changedFiles = changedFiles,
                diff = diff,
                baseDigest = baseDigest,
                candidateDigest = candidateDigest,
                digest = digest,
                risk = risk,
                promptSizeChars = promptSizeChars,
                issues = issues,
            ),
            candidateFiles = candidateFiles,
        )
    }

    /**
     * Stage 2: CAS apply.
     *
     * @param expectedDigest the bound digest from the preview the user
     *   approved. Re-reads BOTH the workspace candidate and the currently
     *   installed version; any change since the preview fails the CAS and the
     *   old approval is rejected (no automatic overwrite).
     */
    suspend fun apply(
        name: String,
        candidateFiles: Map<String, String>,
        sessionId: String,
        runId: String?,
        expectedDigest: String,
    ): ApplyResult {
        val preparation = prepare(name, candidateFiles)
        if (preparation is Preparation.Rejected) return ApplyResult.Rejected(preparation.errors)
        val ready = preparation as Preparation.Ready

        val currentBase = readInstalledFiles(ready.preview.name)
        val bound = ContentDigest.bind(filesDigest(currentBase), ready.preview.candidateDigest)
        if (bound != expectedDigest) {
            return ApplyResult.Stale(
                "The skill or its installed version changed after the preview; re-run skill_preview and approve the new digest."
            )
        }

        val promoted = skillManager.promoteSkill(
            ready.preview.name,
            ready.candidateFiles,
            SkillPreviousMeta(
                replacedDigest = ready.preview.baseDigest,
                candidateDigest = ready.preview.candidateDigest,
                appliedAtMs = System.currentTimeMillis(),
            ),
        )
        if (!promoted) {
            return ApplyResult.Rejected(listOf("Atomic replace of skill '${ready.preview.name}' failed; the previous version was kept"))
        }
        ledger?.recordApproval(
            CasAudit.outcome(
                capability = Capability.SKILL_PROMOTE,
                toolName = "skill_promote",
                sessionId = sessionId,
                runId = runId,
                digest = expectedDigest,
                source = "user",
                outcome = "applied",
                oldDigest = ready.preview.baseDigest,
                newDigest = ready.preview.candidateDigest,
            )
        )
        return ApplyResult.Applied(name = ready.preview.name, fileCount = ready.candidateFiles.size)
    }

    /**
     * Stage 3: one explicit rollback. Restores the kept previous version over
     * the current one; the previous snapshot is deleted afterwards, so a
     * second rollback is impossible. CAS: the current version must still be
     * the promoted candidate (someone may have promoted again meanwhile).
     */
    suspend fun rollback(name: String, sessionId: String, runId: String?): RollbackResult {
        val meta = skillManager.previousSkillMeta(name)
        if (meta == null) {
            return RollbackResult.NoPrevious("Skill '$name' has no previous version to roll back to")
        }
        val current = readInstalledFiles(name)
        val currentDigest = filesDigest(current)
        if (currentDigest != meta.candidateDigest) {
            return RollbackResult.Stale(
                "The installed skill changed again after the promotion; a rollback would overwrite the newer version."
            )
        }
        if (!skillManager.rollbackSkill(name)) {
            return RollbackResult.NoPrevious("Rollback failed: the previous version could not be restored")
        }
        ledger?.recordApproval(
            CasAudit.outcome(
                capability = Capability.SKILL_PROMOTE,
                toolName = "skill_rollback",
                sessionId = sessionId,
                runId = runId,
                digest = meta.candidateDigest,
                source = "user",
                outcome = "rolled_back",
                oldDigest = meta.candidateDigest,
                newDigest = meta.replacedDigest,
            )
        )
        return RollbackResult.RolledBack(name)
    }

    private fun readInstalledFiles(name: String): Map<String, String> {
        val dir = skillManager.getSkillDir(name) ?: return emptyMap()
        val files = linkedMapOf<String, String>()
        walkSkillFiles(dir).forEach { (file, relative) ->
            if (relative.startsWith(".")) return@forEach
            val content = runCatching {
                file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (total > SkillReadBoundary.MAX_SKILL_FILE_BYTES - read) return@use null
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.toByteArray().decodeToString()
                }
            }.getOrNull() ?: return@forEach
            files[relative] = content
        }
        return files
    }

    private fun changedPaths(base: Map<String, String>, candidate: Map<String, String>): List<String> {
        val all = (base.keys + candidate.keys).toSortedSet()
        return all.filter { base[it] != candidate[it] }
    }
}

/** Canonical digest over a files map: sorted by relative path, NUL-separated. */
internal fun filesDigest(files: Map<String, String>): String {
    val canonical = files.toSortedMap().entries.joinToString("\u0000") { (path, content) ->
        "$path\u0000$content"
    }
    return ContentDigest.sha256(canonical)
}
