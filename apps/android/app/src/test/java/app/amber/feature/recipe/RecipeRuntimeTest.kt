package app.amber.feature.recipe

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.Tool
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.files.CasTestFixtures
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.ToolEffect
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.ToolLedgerContext
import app.amber.feature.tools.ToolEffectClass
import app.amber.feature.tools.ToolRisk
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.ToolSearchIndex
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * P4-01 declarative recipe (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §10 P4-01) — acceptance coverage:
 *
 *  - schema validation rejects manifests carrying code (unknown code fields,
 *    arbitrary-code primitives);
 *  - unknown primitives are rejected at import;
 *  - an imported recipe is discoverable by tool_search without a restart;
 *  - a mid-run registry update does not change the fixed per-round snapshot,
 *    and step outputs flow as structured JSON (typed references);
 *  - write steps never bypass approval or the effect ledger;
 *  - rollback makes the newer version unselectable; deletion removes the
 *    recipe from discovery;
 *  - CAS apply rejects stale approvals (base+candidate binding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecipeRuntimeTest {

    private lateinit var context: Context
    private lateinit var testRoot: File
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var registry: RecipeRegistry
    private lateinit var ledger: CasTestFixtures.FakeCasLedger
    private val writeCalls = mutableListOf<JsonElement>()

    private val primRead = Tool(
        name = "prim_read",
        description = "Test read primitive",
        execute = { listOf(UIMessagePart.Text("""{"content": "hello"}""")) },
    )

    private fun primWrite() = Tool(
        name = "prim_write",
        description = "Test write primitive",
        execute = { input ->
            writeCalls += input
            listOf(UIMessagePart.Text("""{"ok": true}"""))
        },
    )

    private fun primitives(): ToolRegistry = ToolRegistry.from(listOf(primRead, primWrite()))

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "recipe-runtime-${System.nanoTime()}").apply { mkdirs() }
        registry = RecipeRegistry(
            dataStore = PreferenceDataStoreFactory.create {
                File(testRoot, "recipe.preferences_pb")
            },
            json = json,
        )
        ledger = CasTestFixtures.FakeCasLedger()
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    private fun dispatcher() = AgentToolDispatcher(
        json = json,
        permissionDecisionResolver = PermissionDecisionResolver(),
        hooks = emptyList(),
    )

    private fun runContext(
        installed: List<RecipeRecord> = emptyList(),
        runId: String? = null,
        conversationId: String? = null,
        effectLedger: ToolEffectLedger? = null,
        autoApprove: Boolean = false,
        autoApproveHighRisk: Boolean = false,
        installedProvider: (() -> List<RecipeRecord>)? = null,
    ) = RecipeRunContext(
        installed = installed,
        dispatcher = dispatcher(),
        runId = runId,
        conversationId = conversationId,
        ledger = effectLedger,
        autoApproveTools = autoApprove,
        autoApproveHighRiskTools = autoApproveHighRisk,
        autoApprovedToolNames = emptySet(),
        capabilityPermissions = null,
        approvalHistory = null,
        installedProvider = installedProvider ?: { installed },
    )

    private fun transaction() = RecipeImportTransaction(registry, ledger)

    private suspend fun ready(
        tx: RecipeImportTransaction,
        manifest: String,
    ): RecipeImportTransaction.Preparation.Ready {
        val prep = tx.prepare(manifest, primitives())
        assertTrue("expected Ready but was $prep", prep is RecipeImportTransaction.Preparation.Ready)
        return prep as RecipeImportTransaction.Preparation.Ready
    }

    private suspend fun rejected(
        tx: RecipeImportTransaction,
        manifest: String,
    ): RecipeImportTransaction.Preparation.Rejected {
        val prep = tx.prepare(manifest, primitives())
        assertTrue("expected Rejected but was $prep", prep is RecipeImportTransaction.Preparation.Rejected)
        return prep as RecipeImportTransaction.Preparation.Rejected
    }

    private suspend fun definitionOf(name: String): RecipeDefinition? {
        val record = registry.get(name) ?: return null
        return RecipeManifestParser.parse(record.manifestJson).toDefinition(record.manifestJson, primitives())
    }

    private suspend fun install(manifest: String) {
        val tx = transaction()
        val prep = ready(tx, manifest)
        val applied = tx.apply(manifest, "session-1", "run-1", prep.preview.digest, primitives())
        assertTrue("expected Applied but was $applied", applied is RecipeImportTransaction.ApplyResult.Applied)
    }

    // ---- schema validation: no code, known primitives only ----

    @Test
    fun `manifest carrying a code field is rejected`() = runBlocking {
        val tx = transaction()
        val withCodeField = """
            {"schemaVersion": 1, "name": "evil", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}, "code": "evil()"}]}
        """.trimIndent()
        val errors = rejected(tx, withCodeField).errors
        assertTrue("expected a rejection mentioning the code field, got $errors", errors.any { it.contains("code") })
    }

    @Test
    fun `manifest referencing an arbitrary-code primitive is rejected`() = runBlocking {
        val tx = transaction()
        val jsStep = """
            {"schemaVersion": 1, "name": "js", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "eval_javascript", "args": {"code": "1+1"}}]}
        """.trimIndent()
        val errors = rejected(tx, jsStep).errors
        assertTrue("expected arbitrary-code rejection, got $errors", errors.any { it.contains("arbitrary-code") })
    }

    @Test
    fun `manifest referencing a js_cell_run primitive is rejected`() = runBlocking {
        val tx = transaction()
        val jsCell = """
            {"schemaVersion": 1, "name": "jscell", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "js_cell_run", "args": {"cell_id": "c1", "code": "fetch('https://evil.example')"}}]}
        """.trimIndent()
        val errors = rejected(tx, jsCell).errors
        assertTrue("expected arbitrary-code rejection for js_cell_run, got $errors", errors.any { it.contains("arbitrary-code") })
    }

    @Test
    fun `manifest referencing an unknown primitive is rejected`() = runBlocking {
        val tx = transaction()
        val unknown = """
            {"schemaVersion": 1, "name": "ghost", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "not_a_tool", "args": {}}]}
        """.trimIndent()
        val errors = rejected(tx, unknown).errors
        assertTrue("expected unknown-primitive rejection, got $errors", errors.any { it.contains("unknown primitive tool 'not_a_tool'") })
    }

    // ---- import / preview / CAS ----

    @Test
    fun `imported recipe is discovered by tool_search`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "compile", "description": "Demo compile recipe", "version": "1.0.0",
             "inputs": [{"name": "source", "type": "string", "required": true}],
             "outputs": [{"name": "summary", "step": "s2"}],
             "steps": [
                {"id": "s1", "tool": "prim_read", "args": {"path": "${'$'}input.source"}},
                {"id": "s2", "tool": "prim_write", "args": {"content": "${'$'}steps.s1.output.content"}}
             ]}
        """.trimIndent()
        val tx = transaction()
        val prep = ready(tx, manifest)
        assertEquals("high", prep.preview.risk) // write step => high risk
        assertTrue(prep.preview.writeStepCount == 1)
        assertTrue(prep.preview.isNew)

        val applied = tx.apply(manifest, "session-1", "run-1", prep.preview.digest, primitives())
        assertTrue("expected Applied but was $applied", applied is RecipeImportTransaction.ApplyResult.Applied)

        // Audit: digest + recipe name only, never the manifest content.
        val audit = ledger.entries.last()
        assertEquals("applied", audit.outcome)
        assertEquals("recipe.import", audit.capabilityId)
        assertEquals("run-1", audit.runId)

        // The run tool is registered without a restart and tool_search finds it.
        val factory = RecipeToolFactory(registry, json)
        val tools = factory.createTools(
            context = runContext(
                installed = registry.installed(),
                runId = "run-discovery",
                effectLedger = FakeToolEffectLedger(),
            ),
            casLedger = null,
            primitivesProvider = { primitives() },
        )
        assertTrue(tools.any { it.name == "recipe_write_compile" })
        val fullRegistry = ToolRegistry.from(primitives().tools() + tools)
        val payload = ToolSearchIndex(fullRegistry).searchPayload("recipe_write_compile", null, 5)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("recipe tool not found by tool_search: $expanded", "recipe_write_compile" in expanded)
    }

    @Test
    fun `recipe management mutations are high risk mandatory non idempotent writes`() = runBlocking {
        val tools = RecipeToolFactory(registry, json).createTools(
            context = runContext(installed = registry.installed()),
            casLedger = null,
            primitivesProvider = { primitives() },
        )
        val toolRegistry = ToolRegistry.from(tools)
        listOf("recipe_import", "recipe_rollback", "recipe_delete").forEach { name ->
            val metadata = toolRegistry.metadataFor(name)!!
            assertTrue("$name must be mutating", metadata.mutates)
            assertEquals(ToolRisk.High, metadata.risk)
            assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, metadata.effectClass)
            assertTrue("$name must require approval", metadata.mandatoryApproval)
            assertFalse("$name must not auto-approve", metadata.autoApprovable)
        }
    }

    @Test
    fun `apply rejects a stale approval when the installed version changed`() = runBlocking {
        val tx = transaction()
        val v1 = """
            {"schemaVersion": 1, "name": "stale", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        val v2 = """
            {"schemaVersion": 1, "name": "stale", "version": "2.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        val v1Prep = ready(tx, v1)
        val v2Prep = ready(tx, v2)
        val first = tx.apply(v2, "session-1", "run-1", v2Prep.preview.digest, primitives())
        assertTrue(first is RecipeImportTransaction.ApplyResult.Applied)

        // The v1 approval is stale — the installed base changed since its preview.
        val stale = tx.apply(v1, "session-2", "run-2", v1Prep.preview.digest, primitives())
        assertTrue("expected Stale but was $stale", stale is RecipeImportTransaction.ApplyResult.Stale)
        // The v2 content stays installed (never auto-overwritten).
        assertTrue(registry.get("stale")!!.manifestJson.contains("\"version\": \"2.0.0\""))
    }

    @Test
    fun `recipe_preview reports per-step tool and args summary for approval`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "previewable", "version": "1.0.0",
             "inputs": [{"name": "path", "type": "string"}],
             "steps": [
                {"id": "s1", "tool": "prim_read", "args": {"path": "${'$'}input.path"}},
                {"id": "s2", "tool": "prim_write", "args": {"content": "checkpoint", "deep": {"nested": "${'$'}steps.s1.output.content"}}}
             ]}
        """.trimIndent()
        val factory = RecipeToolFactory(registry, json)
        val tools = factory.createTools(
            context = runContext(installed = registry.installed()),
            casLedger = null,
            primitivesProvider = { primitives() },
        )
        val previewTool = tools.first { it.name == "recipe_preview" }
        val parts = previewTool.execute(buildJsonObject { put("manifest", manifest) })
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val preview = json.parseToJsonElement(text).jsonObject
        assertEquals("ready", preview["status"]!!.jsonPrimitive.content)
        // Every step is visible to the approver: tool name + args template —
        // smuggled content can no longer hide behind a bare step count.
        val steps = preview["steps"]!!.jsonArray
        assertEquals(2, steps.size)
        val first = steps[0].jsonObject
        assertEquals("s1", first["id"]!!.jsonPrimitive.content)
        assertEquals("prim_read", first["tool"]!!.jsonPrimitive.content)
        val firstArgs = first["args_preview"]!!.jsonPrimitive.content
        assertTrue("args preview must show the declared args, got $firstArgs", firstArgs.contains("${'$'}input.path"))
        val second = steps[1].jsonObject
        assertEquals("prim_write", second["tool"]!!.jsonPrimitive.content)
        assertTrue(
            "args preview must show nested args too, got $second",
            second["args_preview"]!!.jsonPrimitive.content.contains("checkpoint"),
        )
    }

    @Test
    fun `recipe_import audit uses the conversation id instead of a random session`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "audited", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        val factory = RecipeToolFactory(registry, json)
        val tools = factory.createTools(
            context = runContext(installed = registry.installed(), conversationId = "conv-42"),
            casLedger = ledger,
            primitivesProvider = { primitives() },
        )
        val previewParts = tools.first { it.name == "recipe_preview" }
            .execute(buildJsonObject { put("manifest", manifest) })
        val previewJson = json.parseToJsonElement(
            previewParts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        ).jsonObject
        val importParts = tools.first { it.name == "recipe_import" }
            .execute(
                buildJsonObject {
                    put("manifest", manifest)
                    put("preview_digest", previewJson["digest"]!!.jsonPrimitive.content)
                }
            )
        val importText = importParts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val importJson = json.parseToJsonElement(importText).jsonObject
        assertEquals("expected successful import, got $importText", true, importJson["success"]!!.jsonPrimitive.contentOrNull?.toBoolean())
        // The audit session is the conversation id — deterministic and traceable.
        val audit = ledger.entries.last()
        assertEquals("conv-42", audit.toolCallId)
        assertEquals("applied", audit.outcome)
    }

    // ---- execution: fixed snapshot + structured context + permission/ledger ----

    @Test
    fun `a mid-run registry update does not change the fixed snapshot and step outputs flow as structured JSON`() = runBlocking {
        val v1 = """
            {"schemaVersion": 1, "name": "snap", "version": "1.0.0",
             "inputs": [{"name": "source", "type": "string"}],
             "outputs": [{"name": "summary", "step": "s2"}],
             "steps": [
                {"id": "s1", "tool": "prim_read", "args": {"path": "${'$'}input.source"}},
                {"id": "s2", "tool": "prim_write", "args": {"content": "${'$'}steps.s1.output.content"}}
             ]}
        """.trimIndent()
        install(v1)
        val runner = RecipeRunner(json)
        val ctx = runContext(
            installed = registry.installed(),
            runId = "run-snap",
            effectLedger = FakeToolEffectLedger(),
            autoApprove = true,
            autoApproveHighRisk = true,
        )

        val definitionV1 = definitionOf("snap")!!
        val firstRun = runner.run(definitionV1, buildJsonObject { put("source", "a.md") }, ctx) { primitives() }
        assertEquals("ok", firstRun["status"]!!.jsonPrimitive.content)
        // Structured JSON context: the write step received the parsed output
        // of the read step as a typed reference, not text concatenation.
        assertEquals("hello", writeCalls.last().jsonObject["content"]!!.jsonPrimitive.content)
        // Declared output "summary" maps to step s2's structured output.
        assertEquals("true", firstRun["outputs"]!!.jsonObject["summary"]!!.jsonObject["ok"]!!.jsonPrimitive.content)

        // Mid-round update: import v2 — the installed registry changes…
        val v2 = v1.replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0\"")
            .replace("""{"id": "s2", "tool": "prim_write", "args": {"content": "${'$'}steps.s1.output.content"}}""", """{"id": "s2", "tool": "prim_read", "args": {}}""")
        install(v2)
        assertTrue(registry.get("snap")!!.manifestJson.contains("\"version\": \"2.0.0\""))

        // …but the captured snapshot still runs the v1 steps (the write ran again).
        val writeCallsBefore = writeCalls.size
        val secondRun = runner.run(definitionV1, buildJsonObject { put("source", "a.md") }, ctx) { primitives() }
        assertEquals("ok", secondRun["status"]!!.jsonPrimitive.content)
        assertEquals(writeCallsBefore + 1, writeCalls.size)
        assertEquals("hello", writeCalls.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `inline interpolation of a string value does not quote it`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "interp", "version": "1.0.0",
             "inputs": [{"name": "name", "type": "string"}],
             "outputs": [{"name": "greeting", "step": "s1"}],
             "steps": [
                {"id": "s1", "tool": "prim_write", "args": {"greeting": "Hello {${'$'}input.name}"}}
             ]}
        """.trimIndent()
        install(manifest)
        val runner = RecipeRunner(json)
        val definition = definitionOf("interp")!!
        val ctx = runContext(
            installed = registry.installed(),
            runId = "run-interp",
            effectLedger = FakeToolEffectLedger(),
            autoApprove = true,
            autoApproveHighRisk = true,
        )
        val result = runner.run(definition, buildJsonObject { put("name", "alice") }, ctx) { primitives() }
        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        // "alice" is inlined bare — not dragged in as "\"alice\"".
        assertEquals("Hello alice", writeCalls.last().jsonObject["greeting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `write steps do not bypass approval or the effect ledger`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "writeonly", "version": "1.0.0",
             "steps": [{"id": "w1", "tool": "prim_write", "args": {"x": 1}}]}
        """.trimIndent()
        install(manifest)
        val runner = RecipeRunner(json)
        val definition = definitionOf("writeonly")!!

        // Without auto-approval the write step pauses through the normal
        // WAITING_USER checkpoint: nothing executes, nothing touches the ledger.
        val fakeLedger = FakeToolEffectLedger()
        val blockedCtx = runContext(
            installed = registry.installed(),
            runId = "run-1",
            effectLedger = fakeLedger,
            autoApprove = false,
            autoApproveHighRisk = false,
        )
        val blocked = runner.run(definition, JsonObject(emptyMap()), blockedCtx) { primitives() }
        assertEquals("waiting_user", blocked["status"]!!.jsonPrimitive.content)
        assertEquals("w1", blocked["pending_step"]!!.jsonPrimitive.content)
        assertTrue(writeCalls.isEmpty())
        assertTrue("blocked step must not write to the ledger", fakeLedger.prepared.isEmpty())

        // With explicit auto-approval + durable run the step executes through
        // the full dispatcher chain: PREPARED -> STARTED -> FINISHED.
        val durableCtx = runContext(
            installed = registry.installed(),
            runId = "run-2",
            effectLedger = fakeLedger,
            autoApprove = true,
            autoApproveHighRisk = true,
        )
        val executed = runner.run(definition, JsonObject(emptyMap()), durableCtx) { primitives() }
        assertEquals("ok", executed["status"]!!.jsonPrimitive.content)
        assertEquals(1, writeCalls.size)
        val effect = fakeLedger.prepared.single()
        assertEquals("run-2", effect.runId)
        assertEquals("prim_write", effect.toolName)
        assertTrue(effect.toolCallId.startsWith("recipe:run-2:"))
        assertTrue(effect.toolCallId.endsWith(":writeonly:w1"))
        assertEquals(ToolEffectStatus.PREPARED, effect.status)
        assertTrue(fakeLedger.started.contains(effect.effectId))
        assertTrue(fakeLedger.finished.contains(effect.effectId))
    }

    @Test
    fun `nested approval returns a checkpoint and only the resumed step is approved`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "checkpointed", "version": "1.0.0",
             "steps": [{"id": "w1", "tool": "prim_write", "args": {"x": 1}}]}
        """.trimIndent()
        install(manifest)
        val fakeLedger = FakeToolEffectLedger()
        val context = runContext(
            installed = registry.installed(),
            runId = "run-checkpoint",
            effectLedger = fakeLedger,
            autoApprove = false,
            autoApproveHighRisk = false,
        )
        val definition = definitionOf("checkpointed")!!
        val runner = RecipeRunner(json)

        val waiting = runner.run(definition, JsonObject(emptyMap()), context) { primitives() }
        assertEquals("waiting_user", waiting["status"]!!.jsonPrimitive.content)
        assertEquals("w1", waiting["pending_step"]!!.jsonPrimitive.content)
        assertTrue(writeCalls.isEmpty())
        assertTrue(fakeLedger.prepared.isEmpty())

        val checkpoint = waiting["checkpoint"]!!.jsonObject
        val resumedInput = JsonObject(
            json.parseToJsonElement(RecipeRunner.resumeInput(JsonObject(emptyMap()), checkpoint)).jsonObject +
                (RECIPE_RESUME_APPROVED_INPUT_KEY to kotlinx.serialization.json.JsonPrimitive(true))
        )
        val resumed = runner.run(definition, resumedInput, context) { primitives() }
        assertEquals("ok", resumed["status"]!!.jsonPrimitive.content)
        assertEquals(1, writeCalls.size)
        assertEquals("w1", fakeLedger.prepared.single().toolCallId.substringAfterLast(":"))
    }

    @Test
    fun `outer recipe approval cannot auto execute an inner approval step`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "outerapproval", "version": "1.0.0",
             "steps": [
                {"id": "w1", "tool": "prim_write", "args": {"x": 1}},
                {"id": "w2", "tool": "prim_write", "args": {"x": 2}}
             ]}
        """.trimIndent()
        install(manifest)
        val fakeLedger = FakeToolEffectLedger()
        val recipeContext = runContext(
            installed = registry.installed(),
            runId = "run-outer",
            effectLedger = fakeLedger,
            autoApprove = false,
            autoApproveHighRisk = false,
        )
        val recipeTool = RecipeToolFactory(registry, json)
            .createTools(recipeContext, casLedger = null, primitivesProvider = { primitives() })
            .first { it.name == "recipe_write_outerapproval" }
        val outerCall = UIMessagePart.Tool(
            toolCallId = "outer-call",
            toolName = recipeTool.name,
            input = "{}",
            approvalState = ToolApprovalState.Approved,
        )
        val pending = dispatcher().execute(
            tool = outerCall,
            toolDef = recipeTool,
            ledgerContext = ToolLedgerContext("run-outer", 0, fakeLedger),
        )!!
        assertTrue(pending.isPending)
        assertTrue(writeCalls.isEmpty())
        assertTrue(pending.input.contains(RECIPE_CHECKPOINT_INPUT_KEY))

        val checkpointInput = JsonObject(
            json.parseToJsonElement(pending.input).jsonObject +
                (RECIPE_RESUME_APPROVED_INPUT_KEY to kotlinx.serialization.json.JsonPrimitive(true))
        )
        val resumed = dispatcher().execute(
            tool = pending.copy(input = checkpointInput.toString(), approvalState = ToolApprovalState.Approved),
            toolDef = recipeTool,
            ledgerContext = ToolLedgerContext("run-outer", 0, fakeLedger),
        )!!
        // The checkpoint approval is consumed by w1 only; w2 reaches its own
        // ASK decision and becomes a fresh pending checkpoint.
        assertTrue(resumed.toString(), resumed.isPending)
        assertEquals(1, writeCalls.size)
        assertTrue(resumed.input.contains(RECIPE_CHECKPOINT_INPUT_KEY))
        assertTrue(fakeLedger.prepared.isNotEmpty())
    }

    @Test
    fun `tampered recipe checkpoint provenance fails closed`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "tampered", "version": "1.0.0",
             "steps": [{"id": "w1", "tool": "prim_write", "args": {"x": 1}}]}
        """.trimIndent()
        install(manifest)
        val recipeContext = runContext(
            installed = registry.installed(),
            runId = "run-tampered",
            effectLedger = FakeToolEffectLedger(),
            autoApprove = false,
            autoApproveHighRisk = false,
        )
        val recipeTool = RecipeToolFactory(registry, json)
            .createTools(recipeContext, casLedger = null, primitivesProvider = { primitives() })
            .first { it.name == "recipe_write_tampered" }
        val pending = dispatcher().execute(
            tool = UIMessagePart.Tool(
                toolCallId = "tampered-call",
                toolName = recipeTool.name,
                input = "{}",
                approvalState = ToolApprovalState.Approved,
            ),
            toolDef = recipeTool,
        )!!
        val tampered = json.parseToJsonElement(pending.input).jsonObject
        val tamperedCheckpoint = tampered[RECIPE_CHECKPOINT_INPUT_KEY]!!.jsonObject.toMutableMap()
        tamperedCheckpoint["provenance_digest"] = kotlinx.serialization.json.JsonPrimitive("changed")
        val tamperedInput = JsonObject(
            tampered.toMutableMap().apply {
                put(RECIPE_CHECKPOINT_INPUT_KEY, JsonObject(tamperedCheckpoint))
                put(RECIPE_RESUME_APPROVED_INPUT_KEY, kotlinx.serialization.json.JsonPrimitive(true))
            }
        )
        val resumed = dispatcher().execute(
            tool = pending.copy(input = tamperedInput.toString(), approvalState = ToolApprovalState.Approved),
            toolDef = recipeTool,
        )!!
        assertTrue(resumed.isExecuted)
        assertFalse(resumed.isPending)
        assertTrue(writeCalls.isEmpty())
    }

    @Test
    fun `resume keeps failed steps from the original checkpoint`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "failed_resume", "version": "1.0.0",
             "onFailure": "CONTINUE",
             "steps": [
                {"id": "bad", "tool": "missing_primitive", "args": {}},
                {"id": "w1", "tool": "prim_write", "args": {"x": 1}}
             ]}
        """.trimIndent()
        val definition = RecipeManifestParser.parse(manifest).toDefinition(manifest, primitives())
        val context = runContext(
            runId = "run-failed-resume",
            effectLedger = FakeToolEffectLedger(),
            autoApprove = false,
            autoApproveHighRisk = false,
        )
        val runner = RecipeRunner(json)
        val waiting = runner.run(definition, JsonObject(emptyMap()), context) { primitives() }
        assertEquals("waiting_user", waiting["status"]!!.jsonPrimitive.content)
        assertEquals("bad", waiting["checkpoint"]!!.jsonObject["failed_steps"]!!.jsonArray.single().jsonPrimitive.content)
        val resumedInput = JsonObject(
            json.parseToJsonElement(
                RecipeRunner.resumeInput(JsonObject(emptyMap()), waiting["checkpoint"]!!.jsonObject)
            ).jsonObject +
                (RECIPE_RESUME_APPROVED_INPUT_KEY to kotlinx.serialization.json.JsonPrimitive(true))
        )
        val resumed = runner.run(definition, resumedInput, context) { primitives() }
        assertEquals("failed", resumed["status"]!!.jsonPrimitive.content)
        assertEquals("bad", resumed["failed_steps"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, writeCalls.size)
    }

    @Test
    fun `compensation approval is blocked without claiming compensation`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "compensate_wait", "version": "1.0.0",
             "onFailure": "COMPENSATE",
             "steps": [{"id": "bad", "tool": "missing_primitive", "args": {}}],
             "compensate": [{"id": "undo", "tool": "prim_write", "args": {"undo": true}}]}
        """.trimIndent()
        val definition = RecipeManifestParser.parse(manifest).toDefinition(manifest, primitives())
        val result = RecipeRunner(json).run(
            recipe = definition,
            inputs = JsonObject(emptyMap()),
            context = runContext(
                runId = "run-compensate",
                effectLedger = FakeToolEffectLedger(),
                autoApprove = false,
                autoApproveHighRisk = false,
            ),
            primitivesProvider = { primitives() },
        )
        assertEquals("failed", result["status"]!!.jsonPrimitive.content)
        assertEquals(true, result["compensation_blocked"]!!.jsonPrimitive.contentOrNull?.toBoolean())
        assertNull(result["compensated"])
        assertTrue(writeCalls.isEmpty())
    }

    @Test
    fun `denying a nested checkpoint produces no primitive side effect`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "deny_nested", "version": "1.0.0",
             "steps": [{"id": "w1", "tool": "prim_write", "args": {"x": 1}}]}
        """.trimIndent()
        install(manifest)
        val fakeLedger = FakeToolEffectLedger()
        val recipeContext = runContext(
            installed = registry.installed(),
            runId = "run-deny",
            effectLedger = fakeLedger,
        )
        val recipeTool = RecipeToolFactory(registry, json)
            .createTools(recipeContext, casLedger = null, primitivesProvider = { primitives() })
            .first { it.name == "recipe_write_deny_nested" }
        val pending = dispatcher().execute(
            tool = UIMessagePart.Tool(
                toolCallId = "deny-call",
                toolName = recipeTool.name,
                input = "{}",
                approvalState = ToolApprovalState.Approved,
            ),
            toolDef = recipeTool,
        )!!
        assertTrue(pending.isPending)
        val denied = dispatcher().execute(
            tool = pending.copy(approvalState = ToolApprovalState.Denied("user denied")),
            toolDef = recipeTool,
        )!!
        assertTrue(denied.isExecuted)
        assertTrue(denied.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("denied"))
        assertTrue(writeCalls.isEmpty())
    }

    @Test
    fun `write recipes are not registered without a durable ledger`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "nodurable", "version": "1.0.0",
             "steps": [{"id": "w1", "tool": "prim_write", "args": {"x": 1}}]}
        """.trimIndent()
        install(manifest)
        val tools = RecipeToolFactory(registry, json).createTools(
            context = runContext(installed = registry.installed()),
            casLedger = null,
            primitivesProvider = { primitives() },
        )
        assertFalse(tools.any { it.name == "recipe_write_nodurable" })
    }

    @Test
    fun `recipe factory refreshes its registry snapshot at the next round`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "next_round", "version": "1.0.0",
             "steps": [{"id": "r1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        var snapshot = emptyList<RecipeRecord>()
        val context = runContext(
            installed = snapshot,
            runId = "run-registry",
            effectLedger = FakeToolEffectLedger(),
            installedProvider = { snapshot },
        )
        val factory = RecipeToolFactory(registry, json)
        val firstRound = factory.createTools(context, casLedger = null, primitivesProvider = { primitives() })
        assertFalse(firstRound.any { it.name == "recipe_run_next_round" })

        install(manifest)
        snapshot = registry.installed()
        val nextRound = factory.createTools(context, casLedger = null, primitivesProvider = { primitives() })
        assertTrue(nextRound.any { it.name == "recipe_run_next_round" })
    }

    // ---- lifecycle: rollback and deletion ----

    @Test
    fun `rollback makes the newer version unselectable and works only once`() = runBlocking {
        val tx = transaction()
        val v1 = """
            {"schemaVersion": 1, "name": "rollme", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        val v2 = v1.replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0\"")
        install(v1)
        install(v2)
        assertEquals("2.0.0", definitionOf("rollme")!!.version)

        val rolledBack = tx.rollback("rollme", "session-3", "run-3")
        assertTrue("expected RolledBack but was $rolledBack", rolledBack is RecipeImportTransaction.RollbackResult.RolledBack)
        // The installed version is v1 again — the newer version is not selectable.
        assertEquals("1.0.0", definitionOf("rollme")!!.version)
        assertFalse(registry.get("rollme")!!.manifestJson.contains("\"version\": \"2.0.0\""))

        // One-time rollback: the second attempt has nothing to restore.
        val second = tx.rollback("rollme", "session-4", "run-4")
        assertTrue("expected NoPrevious but was $second", second is RecipeImportTransaction.RollbackResult.NoPrevious)
    }

    @Test
    fun `deleted recipes are no longer discovered`() = runBlocking {
        val manifest = """
            {"schemaVersion": 1, "name": "gone", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        install(manifest)
        assertNotNull(registry.get("gone"))

        val tx = transaction()
        val expectedDigest = registry.get("gone")!!.digest
        val deleted = tx.delete("gone", expectedDigest, "session-1", "run-1")
        assertTrue("expected Deleted but was $deleted", deleted is RecipeImportTransaction.DeleteResult.Deleted)
        assertTrue(registry.installed().isEmpty())

        // No run tool is built for the deleted recipe; tool_search cannot find it.
        val tools = RecipeToolFactory(registry, json).createTools(
            context = runContext(installed = registry.installed()),
            casLedger = null,
            primitivesProvider = { primitives() },
        )
        assertTrue(tools.none { it.name.startsWith("recipe_run_") || it.name.startsWith("recipe_write_") })
        assertNull(registry.get("gone"))
    }

    @Test
    fun `delete rejects a stale expected digest`() = runBlocking {
        val v1 = """
            {"schemaVersion": 1, "name": "delete_stale", "version": "1.0.0",
             "steps": [{"id": "s1", "tool": "prim_read", "args": {}}]}
        """.trimIndent()
        val v2 = v1.replace("\"version\": \"1.0.0\"", "\"version\": \"2.0.0\"")
        install(v1)
        val oldDigest = registry.get("delete_stale")!!.digest
        install(v2)

        val result = transaction().delete("delete_stale", oldDigest, "session-1", "run-1")
        assertTrue("expected stale delete but was $result", result is RecipeImportTransaction.DeleteResult.Stale)
        assertNotNull(registry.get("delete_stale"))
        assertTrue(registry.get("delete_stale")!!.manifestJson.contains("2.0.0"))
    }

    /** In-memory [ToolEffectLedger] fake recording the write-ahead transitions. */
    private class FakeToolEffectLedger : ToolEffectLedger {
        val prepared = mutableListOf<ToolEffect>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()

        override suspend fun prepare(
            runId: String,
            turnId: Int,
            toolCallId: String,
            toolName: String,
            input: String,
            effectClass: ToolEffectClass,
            messagePersistenceCursor: String?,
        ): ToolEffect {
            val effect = ToolEffect(
                effectId = "effect-${prepared.size}",
                runId = runId,
                turnId = turnId,
                toolCallId = toolCallId,
                toolName = toolName,
                argsDigest = app.amber.feature.runtime.argsDigest(input),
                approvalDigest = null,
                effectClass = effectClass,
                status = ToolEffectStatus.PREPARED,
                startedAtMs = 0,
                finishedAtMs = null,
                resultSummary = null,
                resultPayload = null,
                errorCategory = null,
                messagePersistenceCursor = messagePersistenceCursor,
            )
            prepared += effect
            return effect
        }

        override suspend fun get(effectId: String): ToolEffect? = prepared.lastOrNull { it.effectId == effectId }

        override suspend fun getByToolCallId(toolCallId: String): ToolEffect? =
            prepared.lastOrNull { it.toolCallId == toolCallId }

        override suspend fun listByRun(runId: String): List<ToolEffect> = prepared.filter { it.runId == runId }

        override suspend fun listByConversation(conversationId: String): List<ToolEffect> = emptyList()

        override suspend fun listOutcomeUnknown(): List<ToolEffect> = emptyList()

        override suspend fun markStarted(effectId: String, approvalDigest: String) {
            started += effectId
        }

        override suspend fun finish(effectId: String, output: List<UIMessagePart>) {
            finished += effectId
        }

        override suspend fun markResultPersisted(effectId: String) = Unit

        override suspend fun fail(effectId: String, errorCategory: String, output: List<UIMessagePart>) = Unit

        override suspend fun deleteTerminalOlderThan(maxAgeMs: Long): Int = 0

        override suspend fun markOutcomeUnknown(effectId: String, errorCategory: String) = Unit

        override suspend fun reconcile(effectId: String, retry: Boolean, abandonOutput: List<UIMessagePart>) = Unit
    }
}
