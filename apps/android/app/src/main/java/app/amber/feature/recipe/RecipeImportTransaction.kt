package app.amber.feature.recipe

import app.amber.feature.runtime.CasAudit
import app.amber.feature.runtime.CasLedger
import app.amber.feature.runtime.ContentDigest
import app.amber.feature.tools.Capability
import app.amber.feature.tools.ToolRegistry

/**
 * P4-01 recipe import transaction (parity plan §10 P4-01), mirroring the
 * P2-04 skill-promotion paradigm:
 *
 * 1. [prepare] parses + schema-validates the manifest, checks every primitive
 *    against the registered ToolRegistry (arbitrary-code primitives rejected),
 *    computes mutation risk (any write step => "high"), and produces the
 *    preview with base digest (installed version), candidate digest and the
 *    bound approval digest [ContentDigest.bind].
 * 2. The user approves a recipe_import call carrying the bound digest (the
 *    approval is bound to base+candidate through the args-digest guard).
 * 3. [apply] re-validates and CAS-applies through [RecipeRegistry] (stale =>
 *    reject, never auto-overwrite); the replaced version is kept for one
 *    explicit rollback.
 * 4. [rollback] restores the previous version once (CAS) and audits.
 * 5. [delete] removes the recipe; the next round no longer selects it.
 *
 * Audit goes to the P2-01 approval history via [CasLedger] — digests and
 * outcome only, never manifest content.
 */
class RecipeImportTransaction(
    private val registry: RecipeRegistry,
    private val ledger: CasLedger?,
) {

    data class RecipeImportPreview(
        val name: String,
        val version: String,
        val description: String,
        val isNew: Boolean,
        val stepCount: Int,
        val writeStepCount: Int,
        val hasCompensateSteps: Boolean,
        val onFailure: RecipeFailureStrategy,
        val declaredCapability: String?,
        /** "high" when any step mutates state, else "normal". */
        val risk: String,
        val baseDigest: String,
        val candidateDigest: String,
        /** Approval key: bind(baseDigest, candidateDigest). */
        val digest: String,
    )

    sealed class Preparation {
        data class Ready(
            val preview: RecipeImportPreview,
            val definition: RecipeDefinition,
        ) : Preparation()

        data class Rejected(val errors: List<String>) : Preparation()
    }

    sealed class ApplyResult {
        data class Applied(val name: String, val version: String) : ApplyResult()
        data class Stale(val reason: String) : ApplyResult()
        data class Rejected(val errors: List<String>) : ApplyResult()
    }

    sealed class RollbackResult {
        data class RolledBack(val name: String) : RollbackResult()
        data class NoPrevious(val reason: String) : RollbackResult()
        data class Stale(val reason: String) : RollbackResult()
    }

    sealed class DeleteResult {
        data class Deleted(val name: String) : DeleteResult()
        data class NotFound(val name: String) : DeleteResult()
        data class Stale(val reason: String) : DeleteResult()
    }

    /**
     * Stage 1: parse + schema validation + primitive checks + preview + digests.
     * Read-only; never touches the installed registry.
     */
    suspend fun prepare(manifestJson: String, primitiveRegistry: ToolRegistry): Preparation {
        if (manifestJson.length > MAX_RECIPE_MANIFEST_CHARS) {
            return Preparation.Rejected(listOf("Recipe manifest exceeds the $MAX_RECIPE_MANIFEST_CHARS char limit"))
        }
        val manifest = runCatching { RecipeManifestParser.parse(manifestJson) }
            .getOrElse { return Preparation.Rejected(listOf(it.message ?: "Invalid recipe manifest")) }
        val errors = RecipeManifestValidator.validate(manifest, primitiveRegistry)
        if (errors.isNotEmpty()) return Preparation.Rejected(errors)

        val definition = manifest.toDefinition(manifestJson, primitiveRegistry)
        val installed = registry.get(manifest.name)
        val baseDigest = installed?.digest ?: EMPTY_RECIPE_DIGEST
        val candidateDigest = ContentDigest.sha256(manifestJson)
        val digest = ContentDigest.bind(baseDigest, candidateDigest)
        val risk = if (definition.writeStepCount > 0) "high" else "normal"
        return Preparation.Ready(
            preview = RecipeImportPreview(
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                isNew = installed == null,
                stepCount = manifest.steps.size,
                writeStepCount = definition.writeStepCount,
                hasCompensateSteps = manifest.compensate.isNotEmpty(),
                onFailure = manifest.onFailure,
                declaredCapability = manifest.capability,
                risk = risk,
                baseDigest = baseDigest,
                candidateDigest = candidateDigest,
                digest = digest,
            ),
            definition = definition,
        )
    }

    /**
     * Stage 2: re-validate, CAS apply (approval bound to base+candidate) and
     * audit. The [RecipeRegistry] re-reads the installed version inside its
     * edit; a changed base or candidate rejects the stale approval.
     */
    suspend fun apply(
        manifestJson: String,
        sessionId: String,
        runId: String?,
        expectedDigest: String,
        primitiveRegistry: ToolRegistry,
    ): ApplyResult {
        val preparation = prepare(manifestJson, primitiveRegistry)
        if (preparation is Preparation.Rejected) return ApplyResult.Rejected(preparation.errors)
        val ready = preparation as Preparation.Ready
        return when (val applied = registry.apply(ready.preview.name, manifestJson, expectedDigest)) {
            is RecipeRegistryApplyResult.Stale -> ApplyResult.Stale(applied.reason)
            is RecipeRegistryApplyResult.Applied -> {
                ledger?.recordApproval(
                    CasAudit.outcome(
                        capability = Capability.RECIPE_IMPORT,
                        toolName = "recipe_import",
                        sessionId = sessionId,
                        runId = runId,
                        digest = expectedDigest,
                        source = "user",
                        outcome = "applied",
                        oldDigest = ready.preview.baseDigest,
                        newDigest = ready.preview.candidateDigest,
                    )
                )
                ApplyResult.Applied(name = ready.preview.name, version = ready.preview.version)
            }
        }
    }

    /** Stage 3: one explicit rollback of the last import (CAS + audit). */
    suspend fun rollback(name: String, sessionId: String, runId: String?): RollbackResult {
        return when (val result = registry.rollback(name)) {
            is RecipeRegistryRollbackResult.NoPrevious -> RollbackResult.NoPrevious(result.reason)
            is RecipeRegistryRollbackResult.Stale -> RollbackResult.Stale(result.reason)
            is RecipeRegistryRollbackResult.RolledBack -> {
                ledger?.recordApproval(
                    CasAudit.outcome(
                        capability = Capability.RECIPE_IMPORT,
                        toolName = "recipe_rollback",
                        sessionId = sessionId,
                        runId = runId,
                        digest = "",
                        source = "user",
                        outcome = "rolled_back",
                    )
                )
                RollbackResult.RolledBack(name)
            }
        }
    }

    /** Delete an installed recipe only when its approved digest is still current. */
    suspend fun delete(
        name: String,
        expectedDigest: String,
        sessionId: String,
        runId: String?,
    ): DeleteResult {
        return when (val result = registry.delete(name, expectedDigest)) {
            is RecipeRegistryDeleteResult.NotFound -> DeleteResult.NotFound(name)
            is RecipeRegistryDeleteResult.Stale -> DeleteResult.Stale(result.reason)
            is RecipeRegistryDeleteResult.Deleted -> {
                ledger?.recordApproval(
                    CasAudit.outcome(
                        capability = Capability.RECIPE_IMPORT,
                        toolName = "recipe_delete",
                        sessionId = sessionId,
                        runId = runId,
                        digest = expectedDigest,
                        source = "user",
                        outcome = "deleted",
                    )
                )
                DeleteResult.Deleted(name)
            }
        }
    }
}
