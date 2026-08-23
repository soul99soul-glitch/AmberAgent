package app.amber.feature.recipe

import app.amber.feature.tools.ToolRegistry
import app.amber.feature.runtime.ContentDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P4-01 declarative recipe schema (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §10 P4-01).
 *
 * A recipe is a declarative tool orchestration: every step references a
 * primitive tool registered in the ToolRegistry by name, with a JSON argument
 * template. The schema is closed — decoding rejects unknown keys, and the
 * validator rejects arbitrary-code primitives — so a recipe can never carry
 * executable code (security red line of the plan).
 */
const val RECIPE_SCHEMA_VERSION = 1

/** Hard manifest size cap (defense in depth; schemas stay small by design). */
const val MAX_RECIPE_MANIFEST_CHARS = 128_000

const val MAX_RECIPE_STEPS = 64

/** Recipe/tool naming: lowercase snake_case so names stay valid tool names. */
val RECIPE_NAME_REGEX = Regex("^[a-z][a-z0-9_]{1,47}$")
val RECIPE_STEP_ID_REGEX = Regex("^[a-z][a-z0-9_]{0,47}$")

/** Names that would collide with the fixed recipe management tools. */
val RECIPE_RESERVED_NAMES = setOf("import", "preview", "rollback", "delete", "list")

/**
 * Primitives whose whole purpose is running arbitrary code. Recipes are
 * declarative orchestration only — referencing these is rejected at import.
 *
 * Membership criterion: every primitive that executes code passed to it as a
 * string argument (regardless of where the code string comes from) belongs on
 * this list. `eval_javascript` and `wm_eval` take code directly;
 * `js_cell_create`/`js_cell_run` take code (or a code-holding cell) that the
 * runtime then evaluates — the smuggled string is executable either way.
 * Wait/terminate/store/load primitives never evaluate user-supplied code and
 * stay out.
 */
val ARBITRARY_CODE_TOOLS = setOf(
    "eval_javascript",
    "wm_eval",
    "js_cell_create",
    "js_cell_run",
)

@Serializable
data class RecipeManifest(
    val schemaVersion: Int = RECIPE_SCHEMA_VERSION,
    val name: String,
    val description: String = "",
    val version: String,
    /** Optional declared security capability id (see Capability.byId). */
    val capability: String? = null,
    /** Default per-step timeout in seconds; a step may override it. */
    val timeoutSeconds: Long? = null,
    val inputs: List<RecipeInput> = emptyList(),
    val outputs: List<RecipeOutput> = emptyList(),
    val steps: List<RecipeStep>,
    val onFailure: RecipeFailureStrategy = RecipeFailureStrategy.STOP,
    /** Compensate steps executed after a failure when onFailure == COMPENSATE. */
    val compensate: List<RecipeStep> = emptyList(),
)

@Serializable
data class RecipeInput(
    val name: String,
    /** "string" | "number" | "boolean" | "json". */
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
)

/** Declared recipe output: the JSON output of [step] exposed as [name]. */
@Serializable
data class RecipeOutput(
    val name: String,
    val step: String,
    val description: String = "",
)

@Serializable
enum class RecipeFailureStrategy {
    /** Abort at the first failed step. */
    STOP,

    /** Record the failure and keep executing the remaining steps. */
    CONTINUE,

    /** Run the declared compensate steps after a failure, then stop. */
    COMPENSATE,
}

@Serializable
data class RecipeStep(
    val id: String,
    /** Primitive tool name, must be registered in the ToolRegistry. */
    val tool: String,
    /**
     * Argument template. Values may reference recipe inputs and previous step
     * outputs as `$input.<name>`, `$steps.<id>.output` (+ optional path), or
     * `{ref}` interpolated inside strings.
     */
    val args: JsonObject = JsonObject(emptyMap()),
    /** Optional simple condition; the step is skipped when not satisfied. */
    val condition: RecipeCondition? = null,
    /** Per-step timeout in seconds; falls back to the manifest default. */
    val timeoutSeconds: Long? = null,
)

@Serializable
data class RecipeCondition(
    /** Reference (`$input.x` / `$steps.<id>.output`) whose value is compared. */
    val field: String,
    /** The step runs only when the resolved value equals this literal. */
    val value: JsonElement,
)

/** Fully validated recipe captured per run — the fixed execution snapshot. */
data class RecipeDefinition(
    val name: String,
    val version: String,
    val description: String,
    val inputs: List<RecipeInput>,
    val outputs: List<RecipeOutput>,
    val steps: List<RecipeStep>,
    val onFailure: RecipeFailureStrategy,
    val compensateSteps: List<RecipeStep>,
    val declaredCapability: String?,
    val defaultTimeoutSeconds: Long?,
    val hasWriteSteps: Boolean,
    val writeStepCount: Int,
    /** Raw manifest — snapshot identity; the tool never re-reads the store. */
    val manifestJson: String,
    val digest: String,
)

/**
 * Strict parser. Unknown keys are rejected (closed schema — this is what stops
 * code fields like `code`/`script` from being smuggled in). The schemaVersion
 * is pre-read leniently so an unsupported future version gets a clear message.
 */
object RecipeManifestParser {
    private val lenient = Json { ignoreUnknownKeys = true }
    private val strict = Json { ignoreUnknownKeys = false }

    fun parse(text: String): RecipeManifest {
        val schemaVersion = runCatching {
            lenient.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        }.getOrDefault(1)
        require(schemaVersion == RECIPE_SCHEMA_VERSION) {
            "Unsupported recipe schemaVersion $schemaVersion (supported: $RECIPE_SCHEMA_VERSION)"
        }
        return strict.decodeFromString(text)
    }
}

/**
 * Template reference syntax: `$input.<path>` or `$steps.<stepId>.output[.<path>]`,
 * optionally `{ref}`-interpolated inside a string.
 */
object RecipeRefs {
    private val EXACT = Regex("""^\$(input|steps)\.([a-zA-Z0-9_.-]+)$""")
    private val INTERP = Regex("""\{(\$(?:input|steps)\.[a-zA-Z0-9_.-]+)\}""")

    sealed class RecipeRef {
        data class Input(val path: List<String>) : RecipeRef()
        data class StepOutput(val stepId: String, val path: List<String>) : RecipeRef()
    }

    fun parse(text: String): RecipeRef? {
        val match = EXACT.matchEntire(text) ?: return null
        val segments = match.groupValues[2].split('.')
        return when (match.groupValues[1]) {
            "input" -> RecipeRef.Input(segments)
            else -> {
                val stepId = segments.firstOrNull() ?: return null
                val rest = segments.drop(1)
                if (rest.firstOrNull() != "output") return null
                RecipeRef.StepOutput(stepId, rest.drop(1))
            }
        }
    }

    /** All exact/interpolated references inside a template value tree. */
    fun collect(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonObject -> element.values.forEach { collect(it, out) }
            is JsonArray -> element.forEach { collect(it, out) }
            is JsonPrimitive -> {
                val text = element.contentOrNull ?: return
                if (EXACT.matches(text)) out += text
                INTERP.findAll(text).forEach { out += it.groupValues[1] }
            }
        }
    }
}

/** Digest of the installed-state "nothing" (empty base for a new recipe). */
val EMPTY_RECIPE_DIGEST: String by lazy { ContentDigest.sha256("") }

/** Write-step / capability analysis used by the import preview and tool metadata. */
fun RecipeManifest.toDefinition(manifestJson: String, primitiveRegistry: ToolRegistry): RecipeDefinition {
    val writeCount = (steps + compensate).count { primitiveRegistry.metadataFor(it.tool)?.mutates == true }
    return RecipeDefinition(
        name = name,
        version = version,
        description = description,
        inputs = inputs,
        outputs = outputs,
        steps = steps,
        onFailure = onFailure,
        compensateSteps = compensate,
        declaredCapability = capability,
        defaultTimeoutSeconds = timeoutSeconds,
        hasWriteSteps = writeCount > 0,
        writeStepCount = writeCount,
        manifestJson = manifestJson,
        digest = ContentDigest.sha256(manifestJson),
    )
}
