package app.amber.feature.recipe

import app.amber.feature.tools.Capability
import app.amber.feature.tools.ToolRegistry

/**
 * P4-01 manifest validation (declarative-only red line):
 *
 *  - closed schema (unknown keys already rejected by the strict parser);
 *  - name/id/version constraints and reserved-name collision checks;
 *  - every step references a registered primitive; arbitrary-code primitives
 *    (`eval_javascript`, `wm_eval`, `js_cell_create`, `js_cell_run`) are
 *    rejected outright;
 *  - template references must resolve against declared inputs and earlier
 *    steps (no forward references, no unknown roots);
 *  - declared capability must be a known capability id;
 *  - timeout bounds.
 */
object RecipeManifestValidator {

    private val INPUT_TYPES = setOf("string", "number", "boolean", "json")

    fun validate(manifest: RecipeManifest, primitiveRegistry: ToolRegistry): List<String> {
        val errors = mutableListOf<String>()

        if (!RECIPE_NAME_REGEX.matches(manifest.name)) {
            errors += "Recipe name '${manifest.name}' must match ${RECIPE_NAME_REGEX.pattern}"
        }
        if (manifest.name in RECIPE_RESERVED_NAMES) {
            errors += "Recipe name '${manifest.name}' is reserved for the recipe management tools"
        }
        // The dynamically registered tool name must not collide with an
        // already-registered tool.
        if (primitiveRegistry.metadataFor("recipe_run_${manifest.name}") != null ||
            primitiveRegistry.metadataFor("recipe_write_${manifest.name}") != null
        ) {
            errors += "A tool named 'recipe_${manifest.name}' is already registered"
        }
        if (manifest.version.isBlank()) {
            errors += "Recipe version must not be blank"
        } else if (manifest.version.length > 64) {
            errors += "Recipe version must not exceed 64 chars"
        }
        if (manifest.timeoutSeconds != null && (manifest.timeoutSeconds < 1 || manifest.timeoutSeconds > 3600)) {
            errors += "Manifest timeoutSeconds must be within 1..3600"
        }
        manifest.capability?.let { declared ->
            if (Capability.byId(declared) == null) {
                errors += "Declared capability '$declared' is not a known capability id"
            }
        }

        val inputNames = manifest.inputs.map { it.name }.toSet()
        val duplicateInputs = manifest.inputs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateInputs.isNotEmpty()) {
            errors += "Duplicate input names: ${duplicateInputs.sorted().joinToString(", ")}"
        }
        manifest.inputs.forEach { input ->
            if (!RECIPE_STEP_ID_REGEX.matches(input.name)) {
                errors += "Input name '${input.name}' is invalid"
            }
            if (input.type !in INPUT_TYPES) {
                errors += "Input '${input.name}' has unsupported type '${input.type}'"
            }
        }

        val duplicateOutputs = manifest.outputs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicateOutputs.isNotEmpty()) {
            errors += "Duplicate output names: ${duplicateOutputs.sorted().joinToString(", ")}"
        }

        val stepIds = manifest.steps.map { it.id }
        val stepIdSet = stepIds.toSet()
        if (manifest.steps.isEmpty()) {
            errors += "Recipe must declare at least one step"
        } else if (manifest.steps.size > MAX_RECIPE_STEPS) {
            errors += "Recipe exceeds the $MAX_RECIPE_STEPS step limit"
        }
        val duplicateSteps = manifest.steps.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateSteps.isNotEmpty()) {
            errors += "Duplicate step ids: ${duplicateSteps.sorted().joinToString(", ")}"
        }

        manifest.steps.forEachIndexed { index, step ->
            errors += validateStep(
                step = step,
                label = "step ${step.id}",
                primitiveRegistry = primitiveRegistry,
                inputNames = inputNames,
                allowedStepIds = stepIds.take(index).toSet(),
            )
        }
        manifest.outputs.forEach { output ->
            if (output.step !in stepIdSet) {
                errors += "Output '${output.name}' references unknown step '${output.step}'"
            }
        }

        // Compensate steps: same rules, but they may reference any main step.
        val compensateIds = manifest.compensate.map { it.id }.toSet()
        val duplicateCompensate = manifest.compensate.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        if (duplicateCompensate.isNotEmpty()) {
            errors += "Duplicate compensate step ids: ${duplicateCompensate.sorted().joinToString(", ")}"
        }
        manifest.compensate.forEach { step ->
            errors += validateStep(
                step = step,
                label = "compensate step ${step.id}",
                primitiveRegistry = primitiveRegistry,
                inputNames = inputNames,
                allowedStepIds = stepIdSet,
            )
        }

        return errors
    }

    private fun validateStep(
        step: RecipeStep,
        label: String,
        primitiveRegistry: ToolRegistry,
        inputNames: Set<String>,
        allowedStepIds: Set<String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (!RECIPE_STEP_ID_REGEX.matches(step.id)) {
            errors += "$label has an invalid id"
        }
        if (step.tool in ARBITRARY_CODE_TOOLS) {
            errors += "$label references '${step.tool}', an arbitrary-code primitive — recipes are declarative and cannot carry code"
        }
        if (primitiveRegistry.metadataFor(step.tool) == null) {
            errors += "$label references unknown primitive tool '${step.tool}'"
        }
        if (step.timeoutSeconds != null && (step.timeoutSeconds < 1 || step.timeoutSeconds > 3600)) {
            errors += "$label timeoutSeconds must be within 1..3600"
        }
        val refs = mutableListOf<String>()
        RecipeRefs.collect(step.args, refs)
        step.condition?.let { condition ->
            if (RecipeRefs.parse(condition.field) == null) {
                errors += "$label condition field '${condition.field}' is not a valid reference"
            } else {
                refs += condition.field
            }
        }
        refs.forEach { ref ->
            when (val parsed = RecipeRefs.parse(ref)) {
                is RecipeRefs.RecipeRef.Input -> {
                    if (parsed.path.firstOrNull() !in inputNames) {
                        errors += "$label references unknown input '${parsed.path.firstOrNull().orEmpty()}'"
                    }
                }

                is RecipeRefs.RecipeRef.StepOutput -> {
                    if (parsed.stepId !in allowedStepIds) {
                        errors += "$label references '${parsed.stepId}', which is not an earlier step"
                    }
                }

                null -> errors += "$label has an invalid template reference '$ref'"
            }
        }
        return errors
    }
}
