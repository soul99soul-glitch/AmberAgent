package app.amber.feature.ui.pages.novel

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import androidx.lifecycle.viewModelScope
import app.amber.agent.data.files.CasTestFixtures
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.settings.Settings
import app.amber.feature.novel.workspace.NovelTurnAgent
import app.amber.feature.novel.workspace.NovelTurnArtifact
import app.amber.feature.novel.workspace.NovelTurnDescriptor
import app.amber.feature.novel.workspace.NovelTurnInput
import app.amber.feature.novel.workspace.NovelTurnLauncher
import app.amber.feature.novel.workspace.NovelTurnPayloads
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController
import app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteCoordinator
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novel.workspace.NovelWorkspaceRestoreBridge
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceFocus
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceProjectSettingsStore
import app.amber.feature.novelworkspace.NovelWorkspaceRestoreBoundary
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceSessionsFile
import app.amber.feature.novelworkspace.NovelWorkspaceSessionMessage
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NovelMarkdownWorkspaceViewModelTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var workManager: WorkManagerImpl
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = RuntimeEnvironment.getApplication()
        val configuration = Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = object : ListenableWorker(appContext, workerParameters) {
                    override fun startWork() = SettableFuture.create<Result>()
                }
            })
            .build()
        workManager = WorkManagerImpl(context, configuration)
        WorkManagerImpl.setDelegate(workManager)
    }

    @After
    fun tearDown() {
        workManager.closeDatabase()
        WorkManagerImpl.setDelegate(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `collaboration mode persists across reload and reopen and cannot change during a turn`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted)
        val viewModel = fixture.viewModel

        assertFalse(viewModel.state.value.ghostwriteMode)
        viewModel.setGhostwriteMode(true)
        assertTrue(viewModel.state.value.ghostwriteMode)
        assertTrue(NovelWorkspaceProjectSettingsStore.load(fixture.directory).ghostwriteMode)
        assertEquals(0, scripted.calls.get())

        viewModel.reload()
        assertTrue(awaitState(viewModel) { !it.loading }.ghostwriteMode)
        val reopened = fixture.reopen()
        assertTrue(awaitState(reopened) { !it.loading && it.exists }.ghostwriteMode)

        reopened.setGhostwriteMode(false)
        assertFalse(reopened.state.value.ghostwriteMode)
        assertFalse(NovelWorkspaceProjectSettingsStore.load(fixture.directory).ghostwriteMode)
        reopened.reload()
        assertFalse(awaitState(reopened) { !it.loading }.ghostwriteMode)

        assertTrue(reopened.send("Keep this discussion open"))
        awaitState(reopened) { it.busy && scripted.calls.get() == 1 }
        reopened.setGhostwriteMode(true)
        assertFalse(reopened.state.value.ghostwriteMode)
        assertFalse(NovelWorkspaceProjectSettingsStore.load(fixture.directory).ghostwriteMode)
        reopened.stopTurn()
        awaitState(reopened) { !it.busy }
        Unit
    }

    @Test
    fun `rewrite stop clears busy and can start another turn`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted, unresolved = true)
        val viewModel = fixture.viewModel

        viewModel.rewriteLaterChapters()
        awaitState(viewModel) { it.busy && scripted.calls.get() == 1 }
        assertEquals("partial output", viewModel.state.value.streamingText)

        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy && !it.consistencyChecking }
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.consistencyChecking)
        assertEquals("", viewModel.state.value.streamingText)
        assertEquals("", viewModel.state.value.reasoningText)

        // The cancelled job must not leave the operation gate latched.
        viewModel.rewriteLaterChapters()
        awaitState(viewModel) { it.busy && scripted.calls.get() == 2 }
        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy }
        Unit
    }

    @Test
    fun `send stop keeps the accepted user message visible`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted)
        val viewModel = fixture.viewModel

        val accepted = viewModel.send("accepted user message")
        assertTrue("Send rejected: ${viewModel.state.value.errorMessage}; loading=${viewModel.state.value.loading}; busy=${viewModel.state.value.busy}", accepted)
        awaitState(viewModel) {
            it.busy && it.messages.any { message -> message.content == "accepted user message" } &&
                scripted.calls.get() == 1 && it.streamingText == "partial output"
        }

        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy && it.messages.any { message -> message.kind == "interrupted" } }
        assertTrue(viewModel.state.value.messages.any { it.content == "accepted user message" })
        assertEquals(1, viewModel.state.value.messages.count { it.kind == "interrupted" && it.content == "partial output" })
    }

    @Test
    fun `consistency stop clears both operation flags and can run again`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted, withChapter = true)
        val viewModel = fixture.viewModel

        viewModel.runConsistencyCheck()
        awaitState(viewModel) { it.busy && it.consistencyChecking && scripted.calls.get() == 1 }

        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy && !it.consistencyChecking }
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.consistencyChecking)

        // A second check proves stopTurn cancelled the registered turn job rather
        // than only changing the visible state.
        viewModel.runConsistencyCheck()
        awaitState(viewModel) { it.busy && it.consistencyChecking && scripted.calls.get() == 2 }
        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy && !it.consistencyChecking }
        Unit
    }

    @Test
    fun `rewrite provider failure is visible and the next rewrite is executable`() = runBlocking {
        val scripted = ScriptedKernel(
            ScriptedKernel.Behavior.Fail,
            ScriptedKernel.Behavior.Hang,
        )
        val fixture = createFixture(scripted, unresolved = true)
        val viewModel = fixture.viewModel

        viewModel.rewriteLaterChapters()
        awaitState(viewModel) {
            !it.busy && it.errorMessage == "provider unavailable" && scripted.calls.get() == 1
        }
        assertEquals("provider unavailable", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.consistencyChecking)

        viewModel.rewriteLaterChapters()
        awaitState(viewModel) { it.busy && scripted.calls.get() == 2 }
        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy }
        Unit
    }

    @Test
    fun `consistency failure saves an incomplete host report and can run again`() = runBlocking {
        val scripted = ScriptedKernel(
            ScriptedKernel.Behavior.PartialFail,
            ScriptedKernel.Behavior.Hang,
        )
        val fixture = createFixture(scripted, withChapter = true)
        val viewModel = fixture.viewModel

        viewModel.runConsistencyCheck()
        awaitState(viewModel) {
            !it.busy && !it.consistencyChecking &&
                it.errorMessage == "provider unavailable" && scripted.calls.get() == 1
        }
        val failed = viewModel.state.value
        assertEquals(0, failed.consistencyCheckedChapters)
        assertEquals(1, failed.consistencyTotalChapters)
        val report = checkNotNull(failed.consistencyReport)
        assertTrue(report.contains("Review incomplete"))
        assertTrue(report.contains("0 / 1"))
        assertFalse(report.contains("partial output"))
        assertTrue(failed.messages.any { it.kind == "consistencyAudit" && it.content == report })

        viewModel.runConsistencyCheck()
        awaitState(viewModel) {
            it.busy && it.consistencyChecking && scripted.calls.get() == 2
        }
        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy && !it.consistencyChecking }
        Unit
    }

    @Test
    fun `switching branch after a deep link does not reapply the initial focus`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(
            scripted,
            focus = NovelWorkspaceFocus(branchSlug = "番外线"),
            withFork = true,
        )
        val viewModel = fixture.viewModel

        awaitState(viewModel) { it.branchSlug == "番外线" }
        viewModel.switchBranch("主线")
        awaitState(viewModel) { !it.busy && it.branchSlug == "主线" }
        assertEquals("主线", viewModel.state.value.branchSlug)
    }

    private data class Fixture(
        val viewModel: NovelMarkdownWorkspaceViewModel,
        val directory: File,
        val reopen: () -> NovelMarkdownWorkspaceViewModel,
        val restoreGate: SyncRestoreWriteGate? = null,
        val restoreBridge: NovelWorkspaceRestoreBridge? = null,
    )

    @Test
    fun `stopping a two chapter audit retains validated progress and reopens its report`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.AuditComplete, ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted, chapterCount = 2)
        val viewModel = fixture.viewModel
        val before = NovelWorkspaceStore(fixture.directory).fileTree()

        viewModel.runConsistencyCheck()
        awaitState(viewModel) {
            it.consistencyChecking && it.consistencyCheckedChapters == 1 &&
                it.consistencyTotalChapters == 2 && scripted.calls.get() == 2
        }
        viewModel.stopTurn()
        val stopped = awaitState(viewModel) {
            !it.busy && !it.consistencyChecking &&
                it.messages.any { message -> message.kind == "consistencyAudit" }
        }
        val report = checkNotNull(stopped.consistencyReport)
        assertTrue(report.contains("Stopped; review incomplete"))
        assertTrue(report.contains("1 / 2"))
        assertFalse(report.contains("partial output"))
        assertEquals(1, stopped.consistencyCheckedChapters)
        assertEquals(2, stopped.consistencyTotalChapters)
        assertEquals(null, stopped.errorMessage)
        assertEquals(report, stopped.messages.single { it.kind == "consistencyAudit" }.content)
        assertEquals(before, NovelWorkspaceStore(fixture.directory).fileTree())

        val reopened = fixture.reopen()
        val reopenedState = awaitState(reopened) { !it.loading && it.exists }
        assertEquals(report, reopenedState.messages.single { it.kind == "consistencyAudit" }.content)
        assertEquals(2, scripted.calls.get())
    }

    @Test
    fun `failure on the second chapter keeps the first review and a retry completes the book`() = runBlocking {
        val scripted = ScriptedKernel(
            ScriptedKernel.Behavior.AuditComplete,
            ScriptedKernel.Behavior.PartialFail,
            ScriptedKernel.Behavior.AuditComplete,
            ScriptedKernel.Behavior.AuditComplete,
        )
        val fixture = createFixture(scripted, chapterCount = 2)
        val viewModel = fixture.viewModel
        val before = NovelWorkspaceStore(fixture.directory).fileTree()

        viewModel.runConsistencyCheck()
        val failed = awaitState(viewModel) { !it.busy && !it.consistencyChecking && scripted.calls.get() == 2 }
        val report = checkNotNull(failed.consistencyReport)
        assertEquals("provider unavailable", failed.errorMessage)
        assertEquals(1, failed.consistencyCheckedChapters)
        assertEquals(2, failed.consistencyTotalChapters)
        assertTrue(report.contains("Review incomplete"))
        assertTrue(report.contains("1 / 2"))
        assertFalse(report.contains("partial output"))
        assertEquals(report, failed.messages.single { it.kind == "consistencyAudit" }.content)

        viewModel.runConsistencyCheck()
        val complete = awaitState(viewModel) {
            !it.busy && !it.consistencyChecking && it.consistencyCheckedChapters == 2 && scripted.calls.get() == 4
        }
        assertEquals(null, complete.errorMessage)
        assertTrue(checkNotNull(complete.consistencyReport).contains("Review complete"))
        assertTrue(checkNotNull(complete.consistencyReport).contains("2 / 2"))
        assertEquals(2, complete.messages.count { it.kind == "consistencyAudit" })
        assertEquals(before, NovelWorkspaceStore(fixture.directory).fileTree())
    }

    @Test
    fun `invalid audit JSON clears operation flags without presenting raw output and allows retry`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.InvalidAudit, ScriptedKernel.Behavior.AuditComplete)
        val fixture = createFixture(scripted, withChapter = true)
        val viewModel = fixture.viewModel
        val before = NovelWorkspaceStore(fixture.directory).fileTree()

        viewModel.runConsistencyCheck()
        val failed = awaitState(viewModel) { !it.busy && !it.consistencyChecking && scripted.calls.get() == 1 }
        assertTrue(failed.errorMessage != null)
        assertEquals(0, failed.consistencyCheckedChapters)
        assertEquals(1, failed.consistencyTotalChapters)
        assertTrue(checkNotNull(failed.consistencyReport).contains("Review incomplete"))
        assertTrue(checkNotNull(failed.consistencyReport).contains("0 / 1"))
        assertFalse(checkNotNull(failed.consistencyReport).contains("{invalid review"))

        viewModel.runConsistencyCheck()
        val complete = awaitState(viewModel) {
            !it.busy && !it.consistencyChecking && it.consistencyCheckedChapters == 1 && scripted.calls.get() == 2
        }
        assertEquals(null, complete.errorMessage)
        assertTrue(checkNotNull(complete.consistencyReport).contains("Review complete"))
        assertEquals(before, NovelWorkspaceStore(fixture.directory).fileTree())
    }

    @Test
    fun `restore drops an old partial audit instead of adding it to the imported session`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.AuditComplete, ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted, chapterCount = 2, withRestoreBridge = true)
        val viewModel = fixture.viewModel
        val branch = NovelWorkspaceBranches.list(fixture.directory, "主线").single()
        val branchId = checkNotNull(branch.id)
        val chapterPath = viewModel.state.value.chapters.first().path
        val chapterRaw = NovelWorkspaceStore(fixture.directory).read(chapterPath)!!
        val importedMessage = NovelWorkspaceSessionMessage(
            id = "imported-conversation", role = "assistant", kind = "discussion",
            content = "Imported conversation", createdAt = Instant.EPOCH,
        )
        checkNotNull(fixture.restoreBridge).use {
            viewModel.runConsistencyCheck()
            awaitState(viewModel) { it.consistencyCheckedChapters == 1 && it.consistencyChecking && scripted.calls.get() == 2 }
            val gate = checkNotNull(fixture.restoreGate)
            gate.withRestore(
                affectedFileRoots = setOf(NovelWorkspaceProjectRepository.RELATIVE_ROOT),
                adoptedFileRoots = emptySet(),
            ) {
                File(fixture.directory, chapterPath).writeText(NovelWorkspaceMarkdown.withBody(chapterRaw, "Restored chapter body"))
                File(fixture.directory, ".amber/sessions.json").writeText(Json.encodeToString(
                    NovelWorkspaceSessionsFile.serializer(),
                    NovelWorkspaceSessionsFile(sessions = mapOf(branchId to listOf(importedMessage))),
                ))
                gate.markFileSnapshotAdopted(NovelWorkspaceProjectRepository.RELATIVE_ROOT)
                gate.markDataCommitted()
            }
            val restored = awaitState(viewModel) { !it.loading && !it.busy && !it.consistencyChecking }
            assertEquals(null, restored.consistencyReport)
            assertEquals(0, restored.consistencyCheckedChapters)
            assertEquals(0, restored.consistencyTotalChapters)
            assertEquals(listOf("Imported conversation"), restored.messages.map { it.content })
            assertTrue(restored.messages.none { it.kind == "consistencyAudit" })
            assertEquals(listOf(importedMessage), NovelWorkspaceSessions.load(fixture.directory).sessions.getValue(branchId))
            assertEquals("Restored chapter body", viewModel.readChapter(chapterPath))
        }
    }

    @Test
    fun `branch creation queued before restore cannot fork the restored manuscript`() = runBlocking {
        val fixture = createFixture(ScriptedKernel(ScriptedKernel.Behavior.Hang), withChapter = true)
        val viewModel = fixture.viewModel
        val chapterPath = viewModel.state.value.chapters.single().path
        val original = NovelWorkspaceStore(fixture.directory).read(chapterPath)!!
        val queuedDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(queuedDispatcher)
        val existingJobs = viewModel.viewModelScope.coroutineContext.job.children.toSet()
        viewModel.createBranch("Queued before restore")
        val queuedAction = viewModel.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }

        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            // Archive replacement writes the imported tree directly while author writes are gated.
            File(fixture.directory, chapterPath).writeText(NovelWorkspaceMarkdown.withBody(original, "Restored manuscript"))
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
        val restoredTree = NovelWorkspaceStore(fixture.directory).fileTree()
        Dispatchers.setMain(mainDispatcher)
        queuedDispatcher.scheduler.runCurrent()
        withTimeout(5_000) { queuedAction.join() }

        assertTrue(queuedAction.isCancelled)
        assertEquals(restoredTree, NovelWorkspaceStore(fixture.directory).fileTree())
        assertEquals(listOf("主线"), NovelWorkspaceBranches.list(fixture.directory, "主线").map { it.slug })
        assertFalse(viewModel.state.value.busy)
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `undo queued before restore cannot remove the imported chapter`() = runBlocking {
        val fixture = createFixture(ScriptedKernel(ScriptedKernel.Behavior.Hang), withChapter = true)
        val viewModel = fixture.viewModel
        assertTrue(viewModel.state.value.canUndo)
        val chapterPath = viewModel.state.value.chapters.single().path
        val original = NovelWorkspaceStore(fixture.directory).read(chapterPath)!!
        val queuedDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(queuedDispatcher)
        val existingJobs = viewModel.viewModelScope.coroutineContext.job.children.toSet()
        viewModel.undoLast()
        val queuedAction = viewModel.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }

        NovelWorkspaceRestoreBoundary.beginRestore()
        try {
            File(fixture.directory, chapterPath).writeText(NovelWorkspaceMarkdown.withBody(original, "Imported chapter must survive the old undo"))
        } finally {
            NovelWorkspaceRestoreBoundary.finishRestore()
        }
        val restoredTree = NovelWorkspaceStore(fixture.directory).fileTree()
        val restoredUndo = File(fixture.directory, ".amber/undo.json").readText()
        Dispatchers.setMain(mainDispatcher)
        queuedDispatcher.scheduler.runCurrent()
        withTimeout(5_000) { queuedAction.join() }

        assertTrue(queuedAction.isCancelled)
        assertEquals(restoredTree, NovelWorkspaceStore(fixture.directory).fileTree())
        assertEquals(restoredUndo, File(fixture.directory, ".amber/undo.json").readText())
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `chapter save queued before restore cannot overwrite the imported chapter`() = runBlocking {
        val fixture = createFixture(ScriptedKernel(ScriptedKernel.Behavior.Hang), withChapter = true, withRestoreBridge = true)
        checkNotNull(fixture.restoreBridge).use {
            val viewModel = fixture.viewModel
            val chapterPath = viewModel.state.value.chapters.single().path
            val original = NovelWorkspaceStore(fixture.directory).read(chapterPath)!!
            val queuedDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(queuedDispatcher)
            val existingJobs = viewModel.viewModelScope.coroutineContext.job.children.toSet()
            var saved = false
            viewModel.saveChapterEdit(chapterPath, "Old editor title", "Old editor body") { saved = true }
            val queuedAction = viewModel.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }

            val gate = checkNotNull(fixture.restoreGate)
            gate.withRestore(
                affectedFileRoots = setOf(NovelWorkspaceProjectRepository.RELATIVE_ROOT),
                adoptedFileRoots = emptySet(),
            ) {
                File(fixture.directory, chapterPath).writeText(NovelWorkspaceMarkdown.withBody(original, "Imported manuscript"))
                gate.markDataCommitted()
            }
            val restoredTree = NovelWorkspaceStore(fixture.directory).fileTree()
            val restoredLedger = File(fixture.directory, ".amber/${NovelWorkspaceLedger.STORE_FILE_NAME}").readText()
            Dispatchers.setMain(mainDispatcher)
            queuedDispatcher.scheduler.runCurrent()
            withTimeout(5_000) { queuedAction.join() }
            awaitState(viewModel) { !it.loading && !it.busy }

            assertEquals(restoredTree, NovelWorkspaceStore(fixture.directory).fileTree())
            assertEquals(restoredLedger, File(fixture.directory, ".amber/${NovelWorkspaceLedger.STORE_FILE_NAME}").readText())
            assertFalse(saved)
            assertFalse(viewModel.state.value.busy)
        }
    }

    @Test
    fun `interactive discussion sends previous branch dialogue once and audits stay isolated`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.DiscussionReply, ScriptedKernel.Behavior.DiscussionReply,
            ScriptedKernel.Behavior.AuditComplete)
        val fixture = createFixture(scripted, withChapter = true)
        val branchId = checkNotNull(NovelWorkspaceBranches.list(fixture.directory, "主线").single().id)
        NovelWorkspaceSessions.save(NovelWorkspaceSessionsFile(sessions = mapOf(
            "other-branch" to listOf(NovelWorkspaceSessionMessage("foreign", "assistant", "discussion", "Foreign branch secret", Instant.EPOCH)),
            branchId to listOf(
                NovelWorkspaceSessionMessage("audit", "assistant", "consistencyAudit", "Old audit report", Instant.EPOCH),
                NovelWorkspaceSessionMessage("interrupted", "assistant", "interrupted", "Unfinished earlier reply", Instant.EPOCH),
            ),
        )), fixture.directory)
        val viewModel = fixture.viewModel
        assertTrue(viewModel.send("Suggest two plot directions"))
        awaitState(viewModel) { !it.busy && it.messages.any { message -> message.content == "One: coast. Two: desert." } }
        assertTrue(viewModel.send("Choose your second direction"))
        awaitState(viewModel) { !it.busy && scripted.calls.get() == 2 }

        val second = scripted.requests[1].messages
        val texts = second.map { message -> message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text } }
        assertEquals(1, texts.count { it == "Suggest two plot directions" })
        assertEquals(1, texts.count { it == "One: coast. Two: desert." })
        assertEquals(1, texts.count { it == "Choose your second direction" })
        assertTrue(texts.indexOf("Suggest two plot directions") < texts.indexOf("One: coast. Two: desert."))
        assertTrue(texts.indexOf("One: coast. Two: desert.") < texts.indexOf("Choose your second direction"))
        assertFalse(texts.any { it.contains("Foreign branch secret") })
        assertFalse(texts.any { it.contains("Old audit report") || it.contains("Unfinished earlier reply") })

        viewModel.runConsistencyCheck()
        awaitState(viewModel) { !it.busy && !it.consistencyChecking && scripted.calls.get() == 3 }
        val audit = scripted.requests[2].messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
        assertFalse(audit.any { it.text.contains("Suggest two plot directions") || it.text.contains("One: coast. Two: desert.") })
    }

    @Test
    fun `followup after provider failure preserves user order without treating interrupted output as a reply`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.DiscussionReply, ScriptedKernel.Behavior.PartialFail,
            ScriptedKernel.Behavior.DiscussionReply)
        val viewModel = createFixture(scripted, withChapter = true).viewModel
        assertTrue(viewModel.send("First completed question"))
        awaitState(viewModel) { !it.busy && scripted.calls.get() == 1 }
        assertTrue(viewModel.send("Second interrupted question"))
        awaitState(viewModel) { !it.busy && it.messages.any { message -> message.kind == "interrupted" } }
        assertTrue(viewModel.send("Third followup question"))
        awaitState(viewModel) { !it.busy && scripted.calls.get() == 3 }
        val dialogue = scripted.requests[2].messages.filter { it.role != app.amber.ai.core.MessageRole.SYSTEM }
            .map { it.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { part -> part.text } }
        assertEquals(listOf("First completed question", "One: coast. Two: desert.", "Second interrupted question", "Third followup question"), dialogue)
    }

    @Test
    fun `dot-prefixed character proposal writes a visible markdown card and preserves its display title`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.CharacterProposal)
        val fixture = createFixture(scripted)
        fixture.viewModel.proposeCharacter(".Alice", "A traveler")
        val state = awaitState(fixture.viewModel) { !it.busy && scripted.calls.get() == 1 }
        val cards = NovelWorkspaceStore(fixture.directory).list("setting/characters")
        assertEquals(listOf("setting/characters/characte.md"), cards)
        assertEquals(".Alice", NovelWorkspaceMarkdown.parseFile(NovelWorkspaceStore(fixture.directory).read(cards.single())!!).fields["title"])
        assertEquals(listOf(".Alice"), state.catalog?.settingGroups?.single { it.directory == "characters" }?.entries?.map { it.title })
    }

    @Test
    fun `repeated character proposal keeps the extension and leaves both same-title cards visible`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.CharacterProposal)
        val fixture = createFixture(scripted)
        fixture.viewModel.proposeCharacter("Alice", "A traveler")
        awaitState(fixture.viewModel) { !it.busy && scripted.calls.get() == 1 }
        val firstPath = "setting/characters/alice.md"
        val firstRaw = checkNotNull(NovelWorkspaceStore(fixture.directory).read(firstPath))
        fixture.viewModel.proposeCharacter("Alice", "A different traveler")
        val state = awaitState(fixture.viewModel) { !it.busy && scripted.calls.get() == 2 }
        val cards = NovelWorkspaceStore(fixture.directory).list("setting/characters")
        assertEquals(listOf("setting/characters/alice-2.md", firstPath), cards)
        assertEquals(firstRaw, NovelWorkspaceStore(fixture.directory).read(firstPath))
        assertEquals(listOf("Alice", "Alice"), state.catalog?.settingGroups?.single { it.directory == "characters" }?.entries?.map { it.title })
    }

    @Test
    fun `stopping a followup before new output does not recover the preceding assistant reply`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.DiscussionReply, ScriptedKernel.Behavior.HangBeforeReply)
        val fixture = createFixture(scripted, withChapter = true)
        val viewModel = fixture.viewModel
        assertTrue(viewModel.send("Suggest two plot directions"))
        awaitState(viewModel) { !it.busy && it.messages.any { message -> message.content == "One: coast. Two: desert." } }
        assertTrue(viewModel.send("Choose your second direction"))
        awaitState(viewModel) { it.busy && scripted.calls.get() == 2 }
        assertEquals("", viewModel.state.value.streamingText)
        viewModel.stopTurn()
        awaitState(viewModel) { !it.busy }
        viewModel.reload()
        val reopened = awaitState(viewModel) { !it.loading }
        assertEquals(1, reopened.messages.count { it.content == "One: coast. Two: desert." })
        assertFalse(reopened.messages.any { it.kind == "interrupted" })
    }

    @Test
    fun `accepted send with a session write failure reports an error instead of escaping its coroutine`() = runBlocking {
        val fixture = createFixture(ScriptedKernel(ScriptedKernel.Behavior.DiscussionReply), withChapter = true)
        val viewModel = fixture.viewModel
        val ledgerDirectory = File(fixture.directory, ".amber")
        val preservedDirectory = File(fixture.directory, ".amber-before-io-failure")
        assertTrue(ledgerDirectory.renameTo(preservedDirectory))
        ledgerDirectory.writeText("A regular file cannot hold the sessions destination")
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        try {
            val queuedDispatcher = StandardTestDispatcher()
            Dispatchers.setMain(queuedDispatcher)
            val existingJobs = viewModel.viewModelScope.coroutineContext.job.children.toSet()
            assertTrue(viewModel.send("Save this accepted user message"))
            val sendJob = viewModel.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }
            Dispatchers.setMain(mainDispatcher)
            queuedDispatcher.scheduler.runCurrent()
            withTimeout(5_000) { sendJob.join() }
            assertFalse(viewModel.state.value.busy)
            assertTrue("Session write failure must be visible", viewModel.state.value.errorMessage != null)
            assertTrue("Session write failure escaped viewModelScope: $uncaught", uncaught.isEmpty())
        } finally {
            Dispatchers.setMain(mainDispatcher)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            assertTrue(ledgerDirectory.delete())
            assertTrue(preservedDirectory.renameTo(ledgerDirectory))
        }
    }

    @Test
    fun `ordinary provider failure retains partial output in the visible session`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.PartialFail)
        val viewModel = createFixture(scripted).viewModel
        assertTrue(viewModel.send("Continue this discussion"))
        val state = awaitState(viewModel) { !it.busy && it.messages.any { message -> message.kind == "interrupted" } }
        assertEquals("partial output", state.messages.first { it.kind == "interrupted" }.content)
        viewModel.reload()
        awaitState(viewModel) { !it.loading }
        assertEquals(1, viewModel.state.value.messages.count { it.kind == "interrupted" })
    }

    @Test
    fun `saving candidate can collect after the successful save callback`() = runBlocking {
        val viewModel = createFixture(ScriptedKernel(ScriptedKernel.Behavior.CompletePolish), withChapter = true).viewModel
        val path = viewModel.state.value.chapters.single().path
        var collected = false
        viewModel.saveFileEdit("drafts/edited.md", "Edited candidate") {
            viewModel.collectDraft("drafts/edited.md", app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget.ReplaceChapter(path), onCollected = { collected = true })
        }
        awaitState(viewModel) { !it.busy && collected }
        assertEquals("Edited candidate", viewModel.readFileBody(path))
    }

    @Test
    fun `single chapter polish creates a candidate without opening an automatic job`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.CompletePolish)
        val viewModel = createFixture(scripted, withChapter = true).viewModel
        assertTrue(viewModel.startPolish(1, 1))
        val state = awaitState(viewModel) { !it.busy && it.proposals.isNotEmpty() }
        assertEquals(null, state.ghostwriteJob)
        val proposal = state.proposals.single()
        val store = app.amber.feature.novelworkspace.NovelWorkspaceStore(proposal.projectDirectory)
        assertEquals("Original chapter body", app.amber.feature.novelworkspace.NovelWorkspaceMarkdown.parseFile(store.read(proposal.entries.single().path)!!).body)
        assertEquals("Polished chapter body", proposal.entries.single().content)
    }

    @Test
    fun `missing single polish chapter reports an error without generating`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.CompletePolish)
        val viewModel = createFixture(scripted).viewModel
        assertFalse(viewModel.startPolish(1, 1))
        assertTrue(viewModel.state.value.errorMessage != null)
        assertEquals(0, scripted.calls.get())
    }

    private suspend fun createFixture(
        scripted: ScriptedKernel,
        unresolved: Boolean = false,
        focus: NovelWorkspaceFocus = NovelWorkspaceFocus(),
        withFork: Boolean = false,
        withChapter: Boolean = false,
        chapterCount: Int = if (withChapter) 1 else 0,
        withRestoreBridge: Boolean = false,
    ): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val workspaceRoot = temporary.newFolder("workspace")
        val repository = NovelWorkspaceProjectRepository(workspaceRoot)
        val project = repository.createBlank("View model fixture")
        val directory = project.projectDirectory
        repeat(chapterCount) { index ->
            val draftPath = "drafts/chapter-${index + 1}.md"
            val body = if (index == 0) "Original chapter body" else "Second chapter body"
            NovelWorkspaceStore(directory).write(draftPath, body)
            val branch = NovelWorkspaceBranches.list(directory, "主线").first { it.slug == "主线" }
            NovelWorkspaceRuntime(scripted).collectDraft(
                directory, checkNotNull(branch.id), "主线", draftPath,
                app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget.NewChapter,
                chapterTitle = if (index == 0) "First chapter" else "Second chapter",
            )
        }
        if (withFork) {
            NovelWorkspaceBranches.createBranch(directory, "主线", "番外线")
        }
        if (unresolved) {
            NovelWorkspaceUnresolvedStore.set(
                projectDirectory = directory,
                branchSlug = "主线",
                fromOrdinal = 1,
                sinceCommitId = requireNotNull(NovelWorkspaceLedger.load(directory).head),
            )
        }

        val model = Model(modelId = "test-model", displayName = "Test Model")
        val settings = CasTestFixtures.settingsAggregator(
            context = context,
            testRoot = temporary.newFolder("settings"),
        )
        settings.update(
            Settings.dummy().copy(
                init = false,
                chatModelId = model.id,
                providers = listOf(
                    ProviderSetting.OpenAI(
                        name = "Test Provider",
                        apiKey = "test-key",
                        // Wait for the canonical raw-flow projection below, rather than the
                        // immediate update() value which can precede a cold initial emission.
                        baseUrl = "https://example.test/v1/",
                        models = listOf(model),
                    ),
                ),
            ),
        )
        withTimeout(5_000) {
            settings.settingsFlow.first {
                !it.init && it.chatModelId == model.id &&
                    it.providers.any { provider -> provider.models.any { it.id == model.id } &&
                        provider is ProviderSetting.OpenAI && provider.baseUrl == "https://example.test/v1" }
            }
        }

        val payloads = NovelTurnPayloads()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = NovelTurnDescriptor.value,
                inputClass = NovelTurnInput::class,
                inputSerializer = NovelTurnInput.serializer(),
                artifactSerializer = NovelTurnArtifact.serializer(),
                factory = { NovelTurnAgent(payloads) },
            )
        }
        val runnerScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val runner = InProcessAgentRunner(
            registry = registry,
            eventStore = InMemoryAgentEventStore(),
            runScopeFactory = { id, _ -> LegacyRunScope(runId = id) },
            scope = runnerScope,
        )
        val launcher = NovelTurnLauncher(runner, payloads, runnerScope)
        val controller = NovelWorkspaceGhostwriteController(
            context = context,
            coordinator = NovelWorkspaceGhostwriteCoordinator(
                NovelWorkspaceRuntime(scripted),
                launcher,
            ),
        )
        val restoreGate = if (withRestoreBridge) SyncRestoreWriteGate() else null
        val restoreBridge = restoreGate?.let { NovelWorkspaceRestoreBridge(it, workspaceRoot) {} }
        fun reopen() = NovelMarkdownWorkspaceViewModel(
            projectId = directory.name,
            repository = repository,
            settingsAggregator = settings,
            ghostwriteController = controller,
            turnLauncher = launcher,
            kernel = scripted,
            context = context,
            requestedFocus = focus,
            restoreBridge = restoreBridge,
        )
        val viewModel = reopen()
        awaitState(viewModel) {
            !it.loading && it.exists && (!unresolved || it.unresolvedFromOrdinal == 1)
        }
        return Fixture(viewModel, directory, ::reopen, restoreGate, restoreBridge)
    }

    private suspend fun awaitState(
        viewModel: NovelMarkdownWorkspaceViewModel,
        predicate: (NovelMarkdownWorkspaceUiState) -> Boolean,
    ): NovelMarkdownWorkspaceUiState = withTimeout(5_000) {
        while (true) {
            val current = viewModel.state.value
            if (predicate(current)) break
            delay(5)
        }
        viewModel.state.value
    }

    /** A deterministic RunKernel fake that can hold a turn in-flight or fail it. */
    private class ScriptedKernel(vararg initial: Behavior) : RunKernel {
        enum class Behavior { Hang, Fail, PartialFail, CompletePolish, AuditComplete, InvalidAudit, DiscussionReply, HangBeforeReply, CharacterProposal }

        private val behaviors = initial.toList().also { require(it.isNotEmpty()) }
        val calls = AtomicInteger()
        val requests = Collections.synchronizedList(mutableListOf<GenerationRunSession>())
        override fun run(session: GenerationRunSession) = flow<GenerationChunk> {
            requests.add(session)
            when (behaviors.getOrElse(calls.getAndIncrement()) { behaviors.last() }) {
                Behavior.CharacterProposal -> {
                    val prompt = session.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val path = (Regex("write the complete card to (\\S+)\\. This new node").find(prompt)
                        ?: Regex("把完整人物卡写入 (\\S+)。本轮").find(prompt))!!.groupValues[1]
                    val title = Regex("(?m)^Name: (.+)$").find(prompt)?.groupValues?.get(1)
                        ?: Regex("(?m)^角色名：(.+)$").find(prompt)!!.groupValues[1]
                    session.tools.first { it.name == "novel_workspace_write" }.execute(buildJsonObject {
                        put("path", path)
                        put("content", NovelWorkspaceMarkdown.render(
                            listOf("kind" to "material", "materialKind" to "character", "title" to title),
                            body = "A complete character card",
                        ))
                    })
                    emit(GenerationChunk.Messages(session.messages + UIMessage(
                        role = app.amber.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Character card created")),
                    )))
                }
                Behavior.DiscussionReply -> {
                    emit(GenerationChunk.Messages(session.messages))
                    emit(GenerationChunk.Messages(session.messages + UIMessage(
                        role = app.amber.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("One: coast. Two: desert.")),
                    )))
                }
                Behavior.HangBeforeReply -> {
                    emit(GenerationChunk.Messages(session.messages))
                    awaitCancellation()
                }
                Behavior.AuditComplete -> {
                    val prompt = session.messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    val path = checkNotNull(Regex("(?m)^CHAPTER_PATH: (.+)$").find(prompt)).groupValues[1]
                    val response = buildJsonObject {
                        put("chapterPath", path)
                        put("issues", JsonArray(emptyList()))
                        put("facts", JsonArray(emptyList()))
                    }.toString()
                    emit(GenerationChunk.Messages(session.messages + UIMessage(
                        role = app.amber.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(response)),
                    )))
                }
                Behavior.InvalidAudit -> emit(GenerationChunk.Messages(session.messages + UIMessage(
                    role = app.amber.ai.core.MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("{invalid review")),
                )))
                Behavior.CompletePolish -> emit(
                    GenerationChunk.Messages(session.messages + UIMessage(
                        role = app.amber.ai.core.MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Polished chapter body")),
                    )),
                )
                Behavior.Hang -> {
                    emit(
                        GenerationChunk.Messages(
                            session.messages + UIMessage(
                                role = app.amber.ai.core.MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text("partial output")),
                            ),
                        ),
                    )
                    awaitCancellation()
                }
                Behavior.Fail -> throw IllegalStateException("provider unavailable")
                Behavior.PartialFail -> {
                    emit(
                        GenerationChunk.Messages(
                            session.messages + UIMessage(
                                role = app.amber.ai.core.MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text("partial output")),
                            ),
                        ),
                    )
                    throw IllegalStateException("provider unavailable")
                }
            }
        }
    }
}
