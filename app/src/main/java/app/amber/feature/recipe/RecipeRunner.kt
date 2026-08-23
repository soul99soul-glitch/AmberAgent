package app.amber.feature.recipe

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.CapabilityPermissionState
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.CapabilityPermissionContext
import app.amber.feature.runtime.PermissionDecisionAction
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.runtime.ToolLedgerContext
import app.amber.feature.runtime.argsDigest
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.effectClass
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Reserved JSON arguments used only for a persisted recipe approval resume. */
const val RECIPE_CHECKPOINT_INPUT_KEY = "__amber_recipe_checkpoint"
const val RECIPE_RESUME_APPROVED_INPUT_KEY = "__amber_recipe_resume_approved"
const val RECIPE_INVOCATION_INPUT_KEY = "__amber_recipe_invocation"

/**
 * Per-run execution context for recipes (P4-01). Captured once per generation
 * round in ChatService — the installed snapshot plus the same permission,
 * ledger and approval knobs the round already uses. Recipe steps run through
 * [AgentToolDispatcher] with this context, so no step can bypass the normal
 * permission chain or the durable effect ledger.
 */
data class RecipeRunContext(
    /** Installed recipe snapshot captured at round start (mid-run updates don't apply). */
    val installed: List<RecipeRecord>,
    val dispatcher: AgentToolDispatcher,
    /** Durable run id; null on the non-durable path. */
    val runId: String?,
    /** Conversation (session) identity, used for audit records instead of a random id. */
    val conversationId: String?,
    /** Durable effect ledger; null on the non-durable path. */
    val ledger: ToolEffectLedger?,
    val autoApproveTools: Boolean,
    val autoApproveHighRiskTools: Boolean,
    val autoApprovedToolNames: Set<String>,
    val capabilityPermissions: CapabilityPermissionState?,
    val approvalHistory: CapabilityPermissionStore?,
    val permissionContext: CapabilityPermissionContext? = null,
    /** Re-reads the registry at the next model round; defaults to this snapshot. */
    val installedProvider: () -> List<RecipeRecord> = { installed },
)

private data class RecipeResumeCheckpoint(
    val nextStepIndex: Int,
    val inputDigest: String,
    val pendingStepId: String,
    val stepResults: Map<String, JsonObject>,
    val stepOutputs: Map<String, JsonElement>,
    val failedSteps: List<String>,
    val invocationId: String,
    val provenanceDigest: String,
)

/**
 * P4-01 recipe execution:
 *
 *  - the [RecipeDefinition] passed in is the fixed snapshot — a registry
 *    update mid-round never changes the current run;
 *  - every step resolves its primitive from the run's ToolRegistry and goes
 *    through [AgentToolDispatcher] (permission resolution first; a step that
 *    would need approval returns a persisted checkpoint and pauses the
 *    top-level tool through the normal WAITING_USER flow;
 *  - executed steps go through the full ledger write-ahead protocol;
 *  - step outputs are carried as structured JSON (typed references), never
 *    text concatenation;
 *  - failure strategy comes from the manifest: stop / continue / compensate
 *    (compensate runs the declared steps, else behaves as stop).
 */
class RecipeRunner(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun run(
        recipe: RecipeDefinition,
        inputs: JsonObject,
        context: RecipeRunContext,
        primitivesProvider: () -> ToolRegistry,
    ): JsonObject {
        val primitiveRegistry = primitivesProvider()
        val primitives = primitiveRegistry.tools().associateBy { it.name }
        val effectiveInputs = JsonObject(
            inputs.filterKeys {
                it != RECIPE_CHECKPOINT_INPUT_KEY &&
                    it != RECIPE_RESUME_APPROVED_INPUT_KEY &&
                    it != RECIPE_INVOCATION_INPUT_KEY
            }
        )
        val inputDigest = argsDigest(effectiveInputs.toString())
        val checkpoint = inputs[RECIPE_CHECKPOINT_INPUT_KEY]?.let { checkpointElement ->
            runCatching { parseCheckpoint(checkpointElement, recipe, inputDigest) }.getOrElse { error ->
                return failedRun(recipe, "Invalid recipe approval checkpoint: ${error.message}")
            }
        }
        val invocationSeed = inputs[RECIPE_INVOCATION_INPUT_KEY]?.jsonPrimitive?.contentOrNull
        val invocationId = checkpoint?.invocationId
            ?: invocationSeed?.let { "$it:$inputDigest" }
            ?: UUID.randomUUID().toString()
        val resumeApproved = checkpoint != null &&
            inputs[RECIPE_RESUME_APPROVED_INPUT_KEY]?.jsonPrimitive?.contentOrNull == "true"
        val missing = recipe.inputs.filter { it.required && !effectiveInputs.containsKey(it.name) }
        if (missing.isNotEmpty()) {
            return failedRun(recipe, "Missing required input(s): ${missing.joinToString { it.name }}")
        }

        val stepResults = linkedMapOf<String, JsonObject>().apply {
            checkpoint?.stepResults?.forEach { (id, result) -> put(id, result) }
        }
        val stepOutputs = linkedMapOf<String, JsonElement>().apply {
            checkpoint?.stepOutputs?.forEach { (id, output) -> put(id, output) }
        }
        val failedSteps = checkpoint?.failedSteps?.toMutableList() ?: mutableListOf()
        var compensated = false
        var compensationAttempted = false
        var compensationSucceeded = true
        var compensationBlocked = false

        for ((index, step) in recipe.steps.withIndex()) {
            if (index < (checkpoint?.nextStepIndex ?: 0)) continue
            if (failedSteps.isNotEmpty() && recipe.onFailure != RecipeFailureStrategy.CONTINUE) break
            val (result, output) = executeStep(
                recipe = recipe,
                step = step,
                turnIndex = index,
                inputs = effectiveInputs,
                stepOutputs = stepOutputs,
                context = context,
                primitives = primitives,
                primitiveRegistry = primitiveRegistry,
                invocationId = invocationId,
                approvalGranted = resumeApproved && index == checkpoint?.nextStepIndex,
            )
            stepResults[step.id] = result
            if (output != null) stepOutputs[step.id] = output
            if (result.status() == "waiting_user") {
                return waitingRun(
                    recipe = recipe,
                    step = step,
                    result = result,
                    nextStepIndex = index,
                    inputDigest = inputDigest,
                    stepResults = stepResults,
                    stepOutputs = stepOutputs,
                    failedSteps = failedSteps,
                    invocationId = invocationId,
                )
            }
            if (result.isFailure()) failedSteps += step.id
        }

        if (failedSteps.isNotEmpty() &&
            recipe.onFailure == RecipeFailureStrategy.COMPENSATE &&
            recipe.compensateSteps.isNotEmpty()
        ) {
            compensationAttempted = true
            for ((index, step) in recipe.compensateSteps.withIndex()) {
                val (result, _) = executeStep(
                    recipe = recipe,
                    step = step,
                    turnIndex = recipe.steps.size + index,
                    inputs = effectiveInputs,
                    stepOutputs = stepOutputs,
                    context = context,
                    primitives = primitives,
                    primitiveRegistry = primitiveRegistry,
                    invocationId = invocationId,
                    approvalGranted = false,
                )
                stepResults["compensate:${step.id}"] = result
                when (result.status()) {
                    "waiting_user" -> {
                        compensationSucceeded = false
                        compensationBlocked = true
                        break
                    }
                    "ok", "skipped" -> Unit
                    else -> compensationSucceeded = false
                }
            }
            compensated = compensationSucceeded && !compensationBlocked
        }

        return buildJsonObject {
            put("status", if (failedSteps.isEmpty()) "ok" else "failed")
            put("recipe", recipe.name)
            put("version", recipe.version)
            put("steps", buildJsonObject { stepResults.forEach { (id, result) -> put(id, result) } })
            if (failedSteps.isNotEmpty()) {
                put("failed_steps", buildJsonArray { failedSteps.forEach { add(JsonPrimitive(it)) } })
            }
            if (compensated) put("compensated", true)
            if (compensationBlocked) {
                put("compensation_blocked", true)
                put("error", "Compensation requires approval and was blocked; no compensation was claimed.")
            } else if (compensationAttempted && !compensationSucceeded) {
                put("compensation_failed", true)
            }
            put("outputs", buildJsonObject {
                recipe.outputs.forEach { output ->
                    put(output.name, stepOutputs[output.step] ?: JsonNull)
                }
            })
        }
    }

    private suspend fun executeStep(
        recipe: RecipeDefinition,
        step: RecipeStep,
        turnIndex: Int,
        inputs: JsonObject,
        stepOutputs: Map<String, JsonElement>,
        context: RecipeRunContext,
        primitives: Map<String, app.amber.ai.core.Tool>,
        primitiveRegistry: ToolRegistry,
        invocationId: String,
        approvalGranted: Boolean,
    ): Pair<JsonObject, JsonElement?> {
        if (step.condition != null) {
            val actual = resolveReference(step.condition.field, inputs, stepOutputs)
            if (actual == null || actual != step.condition.value) {
                return buildJsonObject {
                    put("status", "skipped")
                    put("condition", step.condition.field)
                } to null
            }
        }
        val resolvedArgs = try {
            resolveTemplate(step.args, inputs, stepOutputs)
        } catch (error: Throwable) {
            return failedStep("arg_template_error", error.message) to null
        }
        val toolDef = primitives[step.tool]
        if (toolDef == null) {
            return failedStep("unknown_primitive", "Primitive tool '${step.tool}' is not registered in this round") to null
        }
        val primitiveMetadata = primitiveRegistry.metadataFor(step.tool)
        if ((context.runId == null || context.ledger == null) &&
            (primitiveMetadata?.mutates == true || toolDef.effectClass() != ToolEffectClass.READ_ONLY)
        ) {
            return failedStep(
                "blocked",
                "Recipe step '${step.id}' is mutating and requires the durable runtime ledger",
            ) to null
        }
        val toolCall = UIMessagePart.Tool(
            toolCallId = "recipe:${context.runId ?: "runless"}:$invocationId:${recipe.name}:${step.id}",
            toolName = step.tool,
            input = resolvedArgs.toString(),
            approvalState = if (approvalGranted) ToolApprovalState.Approved else ToolApprovalState.Auto,
        )
        val decision = context.dispatcher.resolveDecision(
            toolDef = toolDef,
            tool = toolCall,
            autoApproveTools = context.autoApproveTools,
            autoApproveHighRiskTools = context.autoApproveHighRiskTools,
            autoApprovedToolNames = context.autoApprovedToolNames,
            invocationContext = ToolInvocationContext.Normal,
            capabilityPermissions = context.capabilityPermissions,
            permissionContext = context.permissionContext,
        )
        if (decision.action == PermissionDecisionAction.ASK) {
            return buildJsonObject {
                put("status", "waiting_user")
                put("error", decision.reason)
                put("permission_trace", decision.trace.toJson())
            } to null
        }
        if (decision.action != PermissionDecisionAction.ALLOW) {
            return buildJsonObject {
                put("status", "blocked")
                put("error", decision.reason)
                put("permission_trace", decision.trace.toJson())
            } to null
        }
        val timeoutMs = (step.timeoutSeconds ?: recipe.defaultTimeoutSeconds)?.times(1000)
        val ledgerContext = context.runId?.let { runId ->
            context.ledger?.let { ledger ->
                ToolLedgerContext(runId = runId, turnId = turnIndex, ledger = ledger)
            }
        }
        val executed = if (timeoutMs != null) {
            withTimeoutOrNull(timeoutMs) {
                context.dispatcher.execute(
                    tool = toolCall,
                    toolDef = toolDef,
                    autoApproveTools = context.autoApproveTools,
                    autoApproveHighRiskTools = context.autoApproveHighRiskTools,
                    autoApprovedToolNames = context.autoApprovedToolNames,
                    invocationContext = ToolInvocationContext.Normal,
                    ledgerContext = ledgerContext,
                    capabilityPermissions = context.capabilityPermissions,
                    // The user approved the persisted outer checkpoint. Its
                    // recipe digest + resolved inputs bind this exact step;
                    // there is no separate model-issued toolCallId to look up
                    // in the capability approval history.
                    approvalHistory = context.approvalHistory.takeUnless { approvalGranted },
                    permissionContext = context.permissionContext,
                )
            }
        } else {
            context.dispatcher.execute(
                tool = toolCall,
                toolDef = toolDef,
                autoApproveTools = context.autoApproveTools,
                autoApproveHighRiskTools = context.autoApproveHighRiskTools,
                autoApprovedToolNames = context.autoApprovedToolNames,
                invocationContext = ToolInvocationContext.Normal,
                ledgerContext = ledgerContext,
                    capabilityPermissions = context.capabilityPermissions,
                    approvalHistory = context.approvalHistory.takeUnless { approvalGranted },
                    permissionContext = context.permissionContext,
                )
        }
        if (executed == null) {
            return failedStep("timeout", "Step exceeded its ${step.timeoutSeconds ?: recipe.defaultTimeoutSeconds}s timeout") to null
        }
        val output = stepOutput(executed)
        return buildJsonObject {
            put("status", "ok")
            put("output", output)
        } to output
    }

    /** Structured JSON step output — parsed when the tool emitted JSON. */
    private fun stepOutput(executed: UIMessagePart.Tool): JsonElement {
        val text = executed.output.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        if (text.isBlank()) return buildJsonObject { put("value", "") }
        return runCatching { json.parseToJsonElement(text) }.getOrElse {
            buildJsonObject {
                put("value", JsonPrimitive(text))
            }
        }
    }

    private fun resolveTemplate(
        template: JsonObject,
        inputs: JsonObject,
        stepOutputs: Map<String, JsonElement>,
    ): JsonObject = JsonObject(
        template.mapValues { (_, value) -> resolveValue(value, inputs, stepOutputs) }
    )

    private fun resolveValue(
        value: JsonElement,
        inputs: JsonObject,
        stepOutputs: Map<String, JsonElement>,
    ): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, inner) -> resolveValue(inner, inputs, stepOutputs) })
        is JsonArray -> JsonArray(value.map { resolveValue(it, inputs, stepOutputs) })
        is JsonPrimitive -> {
            val text = value.contentOrNull ?: return value
            val exact = RecipeRefs.parse(text)
            if (exact != null) {
                resolveReference(text, inputs, stepOutputs)
                    ?: throw IllegalArgumentException("Unresolved template reference: $text")
            } else if (text.contains('{') && text.contains('}')) {
                val interpolated = INTERP_REGEX.replace(text) { match ->
                    val ref = match.groupValues[1]
                    val resolved = resolveReference(ref, inputs, stepOutputs)
                        ?: throw IllegalArgumentException("Unresolved template reference: $ref")
                    // Inline interpolation inlines the *value*: a string value
                    // must not drag its JSON quotes along ("alice", not "\"alice\"").
                    // Non-primitive values fall back to their JSON form.
                    when (resolved) {
                        is JsonPrimitive -> resolved.contentOrNull ?: resolved.toString()
                        else -> resolved.toString()
                    }
                }
                JsonPrimitive(interpolated)
            } else {
                value
            }
        }
    }

    private fun resolveReference(
        ref: String,
        inputs: JsonObject,
        stepOutputs: Map<String, JsonElement>,
    ): JsonElement? = when (val parsed = RecipeRefs.parse(ref)) {
        is RecipeRefs.RecipeRef.Input -> navigate(inputs, parsed.path)
        is RecipeRefs.RecipeRef.StepOutput -> {
            val output = stepOutputs[parsed.stepId] ?: return null
            navigate(output, parsed.path)
        }

        null -> null
    }

    private fun navigate(root: JsonElement, path: List<String>): JsonElement? {
        var current: JsonElement = root
        for (segment in path) {
            current = when (current) {
                is JsonObject -> current[segment] ?: return null
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun failedRun(recipe: RecipeDefinition, error: String): JsonObject = buildJsonObject {
        put("status", "failed")
        put("recipe", recipe.name)
        put("version", recipe.version)
        put("error", error)
        put("steps", buildJsonObject {})
        put("outputs", buildJsonObject {})
    }

    private fun waitingRun(
        recipe: RecipeDefinition,
        step: RecipeStep,
        result: JsonObject,
        nextStepIndex: Int,
        inputDigest: String,
        stepResults: Map<String, JsonObject>,
        stepOutputs: Map<String, JsonElement>,
        failedSteps: List<String>,
        invocationId: String,
    ): JsonObject = buildJsonObject {
        put("status", "waiting_user")
        put("recipe", recipe.name)
        put("version", recipe.version)
        put("pending_step", step.id)
        result["error"]?.let { put("error", it) }
        result["permission_trace"]?.let { put("permission_trace", it) }
        put("steps", buildJsonObject { stepResults.forEach { (id, stepResult) -> put(id, stepResult) } })
        put("outputs", buildJsonObject { stepOutputs.forEach { (id, output) -> put(id, output) } })
        put(
            "checkpoint",
            buildCheckpoint(
                recipe = recipe,
                nextStepIndex = nextStepIndex,
                pendingStepId = step.id,
                inputDigest = inputDigest,
                stepResults = stepResults,
                stepOutputs = stepOutputs,
                failedSteps = failedSteps,
                invocationId = invocationId,
            )
        )
    }

    private fun buildCheckpoint(
        recipe: RecipeDefinition,
        nextStepIndex: Int,
        pendingStepId: String,
        inputDigest: String,
        stepResults: Map<String, JsonObject>,
        stepOutputs: Map<String, JsonElement>,
        failedSteps: List<String>,
        invocationId: String,
    ): JsonObject = buildJsonObject {
        val stepResultsJson = buildJsonObject { stepResults.forEach { (id, result) -> put(id, result) } }
        val stepOutputsJson = buildJsonObject { stepOutputs.forEach { (id, output) -> put(id, output) } }
        val failedStepsJson = buildJsonArray { failedSteps.forEach { add(JsonPrimitive(it)) } }
        val provenanceDigest = checkpointProvenanceDigest(
            recipe = recipe,
            nextStepIndex = nextStepIndex,
            pendingStepId = pendingStepId,
            inputDigest = inputDigest,
            stepResults = stepResultsJson,
            stepOutputs = stepOutputsJson,
            failedSteps = failedStepsJson,
            invocationId = invocationId,
        )
        put("recipe", recipe.name)
        put("version", recipe.version)
        put("digest", recipe.digest)
        put("next_step_index", nextStepIndex)
        put("pending_step", pendingStepId)
        put("input_digest", inputDigest)
        put("invocation_id", invocationId)
        put("step_results", stepResultsJson)
        put("step_outputs", stepOutputsJson)
        put("failed_steps", failedStepsJson)
        put("provenance_digest", provenanceDigest)
    }

    private fun parseCheckpoint(
        element: JsonElement,
        recipe: RecipeDefinition,
        expectedInputDigest: String,
    ): RecipeResumeCheckpoint {
        val checkpoint = element.jsonObject
        require(checkpoint["recipe"]?.jsonPrimitive?.contentOrNull == recipe.name) {
            "recipe identity changed"
        }
        require(checkpoint["version"]?.jsonPrimitive?.contentOrNull == recipe.version) {
            "recipe version changed"
        }
        require(checkpoint["digest"]?.jsonPrimitive?.contentOrNull == recipe.digest) {
            "recipe digest changed"
        }
        val nextStepIndex = checkpoint["next_step_index"]?.jsonPrimitive?.intOrNull
            ?: error("missing next_step_index")
        require(nextStepIndex in recipe.steps.indices) { "next_step_index is out of range" }
        val pendingStepId = checkpoint["pending_step"]?.jsonPrimitive?.contentOrNull
            ?: error("missing pending_step")
        require(pendingStepId == recipe.steps[nextStepIndex].id) { "pending step changed" }
        val inputDigest = checkpoint["input_digest"]?.jsonPrimitive?.contentOrNull
            ?: error("missing input_digest")
        require(inputDigest == expectedInputDigest) { "recipe inputs changed" }
        val invocationId = checkpoint["invocation_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("missing invocation_id")
        val stepResults = checkpoint["step_results"]?.jsonObject.orEmpty()
            .mapValues { (_, value) -> value.jsonObject }
        val stepOutputs = checkpoint["step_outputs"]?.jsonObject.orEmpty()
        val failedSteps = checkpoint["failed_steps"]?.jsonArray.orEmpty().map { value ->
            value.jsonPrimitive.contentOrNull ?: error("invalid failed_steps entry")
        }
        val knownStepIds = recipe.steps.mapTo(linkedSetOf()) { it.id }
        require(stepResults.keys.all { it in knownStepIds }) { "unknown step result in checkpoint" }
        require(stepOutputs.keys.all { it in knownStepIds }) { "unknown step output in checkpoint" }
        require(failedSteps.all { it in knownStepIds }) { "unknown failed step in checkpoint" }
        val provenanceDigest = checkpoint["provenance_digest"]?.jsonPrimitive?.contentOrNull
            ?: error("missing provenance_digest")
        require(
            provenanceDigest == checkpointProvenanceDigest(
                recipe = recipe,
                nextStepIndex = nextStepIndex,
                pendingStepId = pendingStepId,
                inputDigest = inputDigest,
                stepResults = checkpoint["step_results"]?.jsonObject ?: JsonObject(emptyMap()),
                stepOutputs = checkpoint["step_outputs"]?.jsonObject ?: JsonObject(emptyMap()),
                failedSteps = checkpoint["failed_steps"]?.jsonArray ?: JsonArray(emptyList<JsonElement>()),
                invocationId = invocationId,
            )
        ) { "recipe checkpoint provenance changed" }
        return RecipeResumeCheckpoint(
            nextStepIndex = nextStepIndex,
            inputDigest = inputDigest,
            pendingStepId = pendingStepId,
            stepResults = stepResults,
            stepOutputs = stepOutputs,
            failedSteps = failedSteps,
            invocationId = invocationId,
            provenanceDigest = provenanceDigest,
        )
    }

    private fun checkpointProvenanceDigest(
        recipe: RecipeDefinition,
        nextStepIndex: Int,
        pendingStepId: String,
        inputDigest: String,
        stepResults: JsonObject,
        stepOutputs: JsonObject,
        failedSteps: JsonArray,
        invocationId: String,
    ): String = argsDigest(
        buildJsonObject {
            put("recipe", recipe.name)
            put("version", recipe.version)
            put("digest", recipe.digest)
            put("next_step_index", nextStepIndex)
            put("pending_step", pendingStepId)
            put("input_digest", inputDigest)
            put("invocation_id", invocationId)
            put("step_results", stepResults)
            put("step_outputs", stepOutputs)
            put("failed_steps", failedSteps)
        }.toString()
    )

    companion object {
        /** Injects an opaque checkpoint into the next top-level tool call. */
        fun resumeInput(inputs: JsonObject, checkpoint: JsonObject): String = buildJsonObject {
            inputs.forEach { (key, value) ->
                if (key != RECIPE_CHECKPOINT_INPUT_KEY && key != RECIPE_RESUME_APPROVED_INPUT_KEY) {
                    put(key, value)
                }
            }
            put(RECIPE_CHECKPOINT_INPUT_KEY, checkpoint)
        }.toString()

        private val INTERP_REGEX = Regex("""\{(\$(?:input|steps)\.[a-zA-Z0-9_.-]+)\}""")
    }

    private fun failedStep(status: String, error: String?): JsonObject = buildJsonObject {
        put("status", status)
        if (error != null) put("error", error)
    }

    private fun JsonObject.status(): String? =
        this["status"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.isFailure(): Boolean =
        status() !in setOf("ok", "skipped", "waiting_user")

}
