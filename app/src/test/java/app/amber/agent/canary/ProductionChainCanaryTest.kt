package app.amber.agent.canary

import android.app.Application
import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.MessageNodeEntity
import app.amber.agent.data.workspace.ArtifactParseStatus
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.agent.data.workspace.ReparseResult
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.components.workspace.WorkspaceFileVM
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Production-chain canaries (Phase 0 of the Android/iOS capability parity plan —
 * docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md).
 *
 * These tests exercise **current production code only** — no test-only
 * substitute runtime. They freeze the baseline behavior so later phases can
 * prove they did not regress it. The Novel and Workspace chains below do not
 * touch the legacy GenerationHandler path nor the future runtime; runtime
 * canaries pin the durable runtime version (runId + `durable_tool_effects` +
 * `typed_run_terminal` flags) and live in `RuntimeChainCanaryTest`.
 *
 * Failure output convention: runtime-chain canaries (Phase 1+) report
 * runId / conversationId / effectId / capability so a failure can be located.
 * The file-based chains below assert on projectId (Novel) and workspace path
 * (Workspace) instead — there is no run yet in those chains.
 *
 * ## Implemented in Phase 1 — see RuntimeChainCanaryTest
 *
 * All four runtime chains that were TODO in Phase 0 are now implemented in
 * `app/amber/agent/canary/RuntimeChainCanaryTest.kt` (same package), calling
 * the real Phase 1 production components (GenerationHandler,
 * AgentToolDispatcher write-ahead, RoomToolEffectLedger, RoomRunTerminalStore,
 * RunRecoveryService, RunOwnershipRegistry, TokenBudgetFitter):
 *
 * - stream → tool call → approval → side effect → tool result → next turn →
 *   durable terminal. Capability: `durable_tool_effects` (P1-02) +
 *   `typed_run_terminal` (P1-03). Asserts the crash between Started and
 *   Finished never re-runs a non-idempotent tool and that failures name
 *   runId/effectId (effect binding in the approval card metadata).
 * - stream → stop → target run cancelled, other runs unaffected. Capability:
 *   `typed_run_terminal` (P1-03/P1-05). Asserts stop is scoped to
 *   (conversationId, runId) via RunOwnershipRegistry and that WAITING_USER is
 *   never reported as COMPLETED.
 * - process death → checkpoint → resume/reconcile. Capabilities:
 *   `durable_tool_effects` (P1-02) — process death is simulated by rebuilding
 *   every component instance over the same persisted store. Asserts
 *   STARTED non-idempotent → OUTCOME_UNKNOWN (never silently re-run),
 *   PREPARED → re-enters approval reusing the same effectId.
 * - prompt assembly → transformer → mailbox/steer → final token fit →
 *   provider. No dedicated capability flag (P1-04). Asserts the final
 *   provider request fits the hard budget after all injections/transformers,
 *   that ContextTooLarge requests are never sent, and that the current user
 *   message + tool results survive the trim.
 *
 * ## Implemented in Phase 0
 *
 * - Workspace create → open → delete (real classes: WorkspaceManager +
 *   WorkspaceFileVM, SAF-free mirror paths; content staged through the real
 *   AndroidX FileProvider).
 * - Workspace artifact create → open → reparse → delete (real classes:
 *   ArtifactRepository + WorkspaceManager + Room artifact tables; mirror-only
 *   storage since Robolectric has no SAF workspace). Capability:
 *   `workspace_artifacts_v2` (Phase 3, P3-01).
 * - Workspace parse → reparse (same artifact chain, covered by the reparse
 *   step above).
 *
 * The legacy novel JSON engine (NovelPackageCodec + NovelReducer import→edit→
 * export→reimport) was removed with the engine cutover; the migration read
 * shell keeps the encoder/decoder for converting old books.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalUuidApi::class)
class ProductionChainCanaryTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun workspaceCreateOpenDeleteThroughProductionClasses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val manager = WorkspaceManager(context)

        // 1. create — production Share→workspace path (stages into the mirror uploads)
        val source = File(context.cacheDir, "canary-source-${System.nanoTime()}.txt")
            .apply { writeText("canary content") }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            source,
        )
        manager.copyUriToUploads(uri, "canary-notes.md")

        // 2. open — production file-browser path (mirror file resolved for preview/share)
        val fileVm = WorkspaceFileVM(context, manager)
        val opened = fileVm.shareableFile("uploads/canary-notes.md")
        assertNotNull("created file must be openable", opened)
        assertEquals("canary content", opened!!.readText())

        // 3. delete — production delete path (same method WorkspaceFileVM.deleteFile uses)
        assertTrue(manager.deleteWorkspaceFile("uploads/canary-notes.md"))
        assertNull(fileVm.shareableFile("uploads/canary-notes.md"))
    }

    @Test
    fun workspaceArtifactCreateOpenReparseDeleteThroughProductionClasses() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspaceManager = WorkspaceManager(context)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = ArtifactRepository(
            dao = database.artifactDao(),
            workspaceManager = workspaceManager,
            messageNodeDao = database.messageNodeDao(),
            conversationDao = database.conversationDao(),
        )

        // 1. create — save a chat message artifact through the production registry
        val conversationId = "canary-conv"
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("canary artifact body")),
        )
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = "assistant-canary",
                title = "Canary",
                nodes = "[]",
                createAt = System.currentTimeMillis(),
                updateAt = System.currentTimeMillis(),
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insert(
            MessageNodeEntity(
                id = Uuid.random().toString(),
                conversationId = conversationId,
                nodeIndex = 0,
                messages = JsonInstant.encodeToString(listOf(message)),
                selectIndex = 0,
            )
        )
        val artifact = repository.saveChatMessage(
            message = message,
            conversationId = conversationId,
            workspaceId = ArtifactRepository.DEFAULT_WORKSPACE_ID,
            includeReasoning = false,
        )
        assertEquals(ArtifactParseStatus.PARSED, artifact.parseStatus)

        // 2. open — production file-locator path (mirror file resolved + read)
        val contentFile = repository.contentFile(artifact)
        assertNotNull("created artifact must have a content file", contentFile)
        assertEquals("canary artifact body", repository.readContent(artifact))

        // 3. reparse — production source-driven parser (source message still present)
        val reparseResult = repository.reparse(artifact.artifactId)
        assertTrue(reparseResult is ReparseResult.Success)
        assertEquals(ArtifactParseStatus.PARSED, repository.get(artifact.artifactId)!!.parseStatus)

        // 4. delete — production delete path (row + content file)
        assertTrue(repository.delete(artifact.artifactId))
        assertNull("artifact row must be gone", repository.get(artifact.artifactId))
        assertNull("content file must be gone", repository.contentFile(artifact))
        database.close()
    }
}
