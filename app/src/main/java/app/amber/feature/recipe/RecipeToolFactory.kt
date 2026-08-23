package app.amber.feature.recipe

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.CasLedger
import app.amber.feature.runtime.ToolExecutionPause
import app.amber.feature.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P4-01 dynamic tool registration: builds the recipe management tools
 * (recipe_preview / recipe_import / recipe_rollback / recipe_delete) plus one
 * run tool per installed recipe from the round's snapshot. Everything is
 * gated by the recipe_runtime capability flag at the call site — no flag, no
 * tools, no import entry.
 *
 * Run tools capture their [RecipeDefinition] at creation, so a registry
 * update mid-round cannot change the current round (fixed snapshot); the next
 * round re-reads the installed registry. Write-step recipes are registered as
 * `recipe_write_<name>` so the registry's name-based heuristics classify them
 * as mutating (approval + non-idempotent effect class); read-only recipes use
 * `recipe_run_<name>`.
 */
class RecipeToolFactory(
    private val registry: RecipeRegistry,
    private val json: Json,
) {
    private val runner = RecipeRunner(json)

    fun createTools(
        context: RecipeRunContext,
        casLedger: CasLedger?,
        primitivesProvider: () -> ToolRegistry,
    ): List<Tool> {
        val transaction = RecipeImportTransaction(registry, casLedger)
        val installed = context.installedProvider()
        return buildList {
            add(recipePreviewTool(transaction, primitivesProvider))
            add(recipeImportTool(transaction, context, primitivesProvider))
            add(recipeRollbackTool(transaction, context))
            add(recipeDeleteTool(transaction, context))
            installed.forEach { record ->
                val definition = runCatching {
                    RecipeManifestParser.parse(record.manifestJson).toDefinition(record.manifestJson, primitivesProvider())
                }.getOrNull()
                // A write recipe is never exposed on the legacy/non-durable
                // path. Read-only recipes can still be useful there, while
                // every mutating primitive remains behind the real ledger.
                if (definition != null && (!definition.hasWriteSteps || context.runId != null && context.ledger != null)) {
                    add(recipeRunTool(definition, context, primitivesProvider))
                }
            }
        }
    }

    private fun recipeRunTool(
        definition: RecipeDefinition,
        context: RecipeRunContext,
        primitivesProvider: () -> ToolRegistry,
    ): Tool {
        val write = definition.hasWriteSteps
        val toolName = if (write) "recipe_write_${definition.name}" else "recipe_run_${definition.name}"
        return Tool(
            name = toolName,
            description = buildString {
                append("Execute the '${definition.name}' recipe (v${definition.version}): ${definition.description} ")
                append("Steps: ${definition.steps.size} (${definition.writeStepCount} write). ")
                append("Inputs: ${definition.inputs.joinToString { it.name }}. On failure: ${definition.onFailure.name.lowercase()}.")
                if (write) append(" This recipe mutates state and requires approval.")
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        definition.inputs.forEach { input ->
                            put(input.name, buildJsonObject {
                                put("type", input.type.wireType())
                                if (input.description.isNotBlank()) put("description", input.description)
                            })
                        }
                    },
                    required = definition.inputs.filter { it.required }.map { it.name }.ifEmpty { null },
                )
            },
            needsApproval = write,
            allowsAutoApproval = !write,
            // 含写步骤即高风险 (plan §P4-01): only the explicit high-risk
            // auto-approval setting can run a write recipe unattended.
            mandatoryApproval = write,
            // Nested primitive calls own their ledger effects. The outer
            // composite effect is only PREPARED by GenerationHandler and is
            // finished by AgentToolDispatcher after the recipe returns; a
            // nested approval leaves it reusable rather than STARTED.
            ledgerManaged = false,
            execute = { input ->
                val inputs = (input as? JsonObject) ?: JsonObject(emptyMap())
                val result = runner.run(definition, inputs, context, primitivesProvider)
                if (result["status"]?.jsonPrimitive?.contentOrNull == "waiting_user") {
                    val checkpoint = result["checkpoint"]?.jsonObject
                        ?: error("Recipe approval checkpoint is missing")
                    throw ToolExecutionPause(
                        resumeInput = RecipeRunner.resumeInput(inputs, checkpoint),
                        message = result["error"]?.jsonPrimitive?.contentOrNull
                            ?: "Recipe step requires approval",
                        resumeProvenance = checkpoint["provenance_digest"]?.jsonPrimitive?.contentOrNull,
                    )
                }
                listOf(UIMessagePart.Text(result.toString()))
            },
        )
    }

    private fun recipePreviewTool(
        transaction: RecipeImportTransaction,
        primitivesProvider: () -> ToolRegistry,
    ) = Tool(
        name = "recipe_preview",
        description = "Preview a declarative recipe manifest before importing: schema validation, primitive checks, mutation risk, per-step tool+args summaries and digests. Returns the digest to pass to recipe_import.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("manifest", stringProp("The full recipe manifest JSON. Recipes are declarative tool orchestrations and never carry code."))
                },
                required = listOf("manifest"),
            )
        },
        execute = { input ->
            val manifest = input.jsonObject["manifest"]?.jsonPrimitive?.contentOrNull
                ?: error("manifest is required")
            when (val preparation = transaction.prepare(manifest, primitivesProvider())) {
                is RecipeImportTransaction.Preparation.Rejected ->
                    error("Recipe manifest rejected: ${preparation.errors.joinToString("; ")}")
                is RecipeImportTransaction.Preparation.Ready -> {
                    val preview = preparation.preview
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "ready")
                                put("name", preview.name)
                                put("version", preview.version)
                                put("description", preview.description)
                                put("is_new", preview.isNew)
                                put("step_count", preview.stepCount)
                                put("write_step_count", preview.writeStepCount)
                                put("has_compensate_steps", preview.hasCompensateSteps)
                                put("on_failure", preview.onFailure.name.lowercase())
                                preview.declaredCapability?.let { put("declared_capability", it) }
                                put("risk", preview.risk)
                                // Step-level summary for approval: each step's
                                // tool + truncated args template, so the user
                                // sees exactly what the recipe would execute
                                // (smuggled content can no longer hide behind a
                                // bare step count).
                                put("steps", buildJsonArray {
                                    preparation.definition.steps.forEach { step ->
                                        add(buildJsonObject {
                                            put("id", step.id)
                                            put("tool", step.tool)
                                            put("args_preview", step.args.toString().take(ARGS_PREVIEW_CHARS))
                                        })
                                    }
                                })
                                put("base_digest", preview.baseDigest)
                                put("candidate_digest", preview.candidateDigest)
                                put("digest", preview.digest)
                                put("import_instruction", "Call recipe_import with the same manifest and preview_digest=${preview.digest} after the user approves.")
                            }.toString()
                        )
                    )
                }
            }
        },
    )

    /**
     * Stage 2: apply an approved recipe candidate. The call carries the bound
     * digest from recipe_preview; the approval binds base+candidate through
     * the args-digest guard, and apply re-reads the installed version before
     * the CAS write (stale -> reject, never auto-overwrite). The replaced
     * version is kept for one explicit rollback; audit goes to the approval
     * history.
     */
    private fun recipeImportTool(
        transaction: RecipeImportTransaction,
        context: RecipeRunContext,
        primitivesProvider: () -> ToolRegistry,
    ) = Tool(
        name = "recipe_import",
        description = "Import a declarative recipe after recipe_preview. The preview_digest must match the current candidate; stale approvals are rejected. Imported recipes become discoverable via tool_search in the next round.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("manifest", stringProp("The full recipe manifest JSON, identical to the one previewed."))
                    put("preview_digest", stringProp("The digest returned by recipe_preview for this candidate."))
                },
                required = listOf("manifest", "preview_digest"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            val manifest = input.jsonObject["manifest"]?.jsonPrimitive?.contentOrNull
                ?: error("manifest is required")
            val previewDigest = input.jsonObject["preview_digest"]?.jsonPrimitive?.contentOrNull
                ?: error("preview_digest is required")
            val sessionId = context.conversationId.orEmpty()
            when (val result = transaction.apply(manifest, sessionId, context.runId, previewDigest, primitivesProvider())) {
                is RecipeImportTransaction.ApplyResult.Rejected ->
                    error("Recipe rejected: ${result.errors.joinToString("; ")}")
                is RecipeImportTransaction.ApplyResult.Stale -> error(result.reason)
                is RecipeImportTransaction.ApplyResult.Applied -> {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("name", result.name)
                                put("version", result.version)
                                put("digest", previewDigest)
                                put("note", "The recipe is registered and discoverable via tool_search in the next round.")
                            }.toString()
                        )
                    )
                }
            }
        },
    )

    private fun recipeRollbackTool(
        transaction: RecipeImportTransaction,
        context: RecipeRunContext,
    ) = Tool(
        name = "recipe_rollback",
        description = "Roll back the most recent import of an installed recipe to its previous version. Can be executed only once per import.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Recipe name to roll back."))
                },
                required = listOf("name"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
            val sessionId = context.conversationId.orEmpty()
            when (val result = transaction.rollback(name, sessionId, context.runId)) {
                is RecipeImportTransaction.RollbackResult.NoPrevious -> error(result.reason)
                is RecipeImportTransaction.RollbackResult.Stale -> error(result.reason)
                is RecipeImportTransaction.RollbackResult.RolledBack -> {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("name", result.name)
                                put("rolled_back", true)
                                put("note", "The previous version was restored; a second rollback is not possible.")
                            }.toString()
                        )
                    )
                }
            }
        },
    )

    private fun recipeDeleteTool(
        transaction: RecipeImportTransaction,
        context: RecipeRunContext,
    ) = Tool(
        name = "recipe_delete",
        description = "Delete an installed recipe only when expected_digest matches the currently installed recipe. The next round no longer selects it.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", stringProp("Recipe name to delete."))
                    put("expected_digest", stringProp("The installed recipe digest approved for deletion; stale digests are rejected."))
                },
                required = listOf("name", "expected_digest"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
            val expectedDigest = input.jsonObject["expected_digest"]?.jsonPrimitive?.contentOrNull
                ?: error("expected_digest is required")
            val sessionId = context.conversationId.orEmpty()
            when (val result = transaction.delete(name, expectedDigest, sessionId, context.runId)) {
                is RecipeImportTransaction.DeleteResult.NotFound -> error("Recipe '$name' is not installed")
                is RecipeImportTransaction.DeleteResult.Stale -> error(result.reason)
                is RecipeImportTransaction.DeleteResult.Deleted -> {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("success", true)
                                put("name", result.name)
                                put("deleted", true)
                                put("note", "The recipe is removed; tool_search stops selecting it from the next round.")
                            }.toString()
                        )
                    )
                }
            }
        },
    )

    private fun String.wireType(): String = when (this) {
        "number" -> "number"
        "boolean" -> "boolean"
        "json" -> "object"
        else -> "string"
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private companion object {
        /** Step-args template preview length in recipe_preview output. */
        const val ARGS_PREVIEW_CHARS = 200
    }
}
