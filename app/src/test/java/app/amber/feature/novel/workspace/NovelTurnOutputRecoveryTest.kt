package app.amber.feature.novel.workspace

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceSessions
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import app.amber.feature.novelworkspace.NovelWorkspaceTurnOutputs
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NovelTurnOutputRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun launcher(scope: CoroutineScope): NovelTurnLauncher {
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
        val runner = InProcessAgentRunner(
            registry = registry,
            eventStore = InMemoryAgentEventStore(),
            runScopeFactory = { id, _ -> LegacyRunScope(runId = id) },
            scope = scope,
        )
        return NovelTurnLauncher(runner, payloads, scope)
    }

    private fun request(directory: File) = NovelWorkspaceRuntime.TurnRequest(
        projectDirectory = directory,
        branchId = "branch-1",
        branchSlug = "主线",
        userText = "继续",
        systemPrompt = "",
        settings = Settings(),
        model = Model(),
    )

    private fun partial(session: GenerationRunSession) = GenerationChunk.Messages(
        session.messages + UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("正在写的一段正文")),
        ),
    )

    @Test fun cancellingTheActualLauncherFlushesOutputBeforeRollbackSettles() = runTest {
        val directory = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Stop fixture").projectDirectory
        val before = NovelWorkspaceStore(directory).fileTree()
        val sawDelta = CompletableDeferred<Unit>()
        val kernel = object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                emit(partial(session))
                awaitCancellation()
            }
        }
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val handle = launcher(runnerScope).launch(request(directory), NovelWorkspaceRuntime(kernel))
            val collection = launch {
                handle.events.collect { event ->
                    if (event is NovelWorkspaceRuntime.TurnEvent.Delta) sawDelta.complete(Unit)
                }
            }
            sawDelta.await()
            collection.cancelAndJoin()
            assertEquals(RunStatus.CANCELLED, handle.awaitTerminal())
            val snapshot = NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single()
            assertEquals(handle.runId.value, snapshot.runId)
            assertFalse(snapshot.completed)
            assertEquals(before, NovelWorkspaceStore(directory).fileTree())
            // A reopened project consumes only the local text; no model or tools rerun.
            NovelWorkspaceTurnOutputs.recoverToSessions(File(directory.path), "branch-1")
            val recovered = NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single()
            assertEquals("interrupted", recovered.kind)
            assertEquals("正在写的一段正文", recovered.content)
        } finally {
            runnerScope.cancel()
        }
    }

    @Test fun providerFailureThroughTheAgentReturnsPartialTextToTheSameBranch() = runTest {
        val directory = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Failure fixture").projectDirectory
        val kernel = object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                emit(partial(session))
                error("provider interrupted")
            }
        }
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val handle = launcher(runnerScope).launch(request(directory), NovelWorkspaceRuntime(kernel))
            val events = handle.events.toList()
            assertTrue(events.last() is NovelWorkspaceRuntime.TurnEvent.Failed)
            NovelWorkspaceTurnOutputs.recoverToSessions(directory, "branch-1")
            assertEquals("正在写的一段正文", NovelWorkspaceSessions.load(directory).sessions.getValue("branch-1").single().content)
            assertTrue(NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").isEmpty())
        } finally {
            runnerScope.cancel()
        }
    }

    @Test fun cancellingAfterTwoWritesToANewFileDeletesItAndRetainsAuthorOutput() = runTest {
        val directory = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Rollback fixture").projectDirectory
        val before = NovelWorkspaceStore(directory).fileTree()
        val sawDelta = CompletableDeferred<Unit>()
        val kernel = object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                val write = session.tools.first { it.name == "novel_workspace_write" }
                for (content in listOf("First draft", "Second draft")) {
                    write.execute(buildJsonObject { put("path", "drafts/new.md"); put("content", content) })
                }
                emit(partial(session))
                awaitCancellation()
            }
        }
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val handle = launcher(runnerScope).launch(request(directory), NovelWorkspaceRuntime(kernel))
            val collection = launch {
                handle.events.collect { if (it is NovelWorkspaceRuntime.TurnEvent.Delta) sawDelta.complete(Unit) }
            }
            sawDelta.await()
            collection.cancelAndJoin()
            assertEquals(RunStatus.CANCELLED, handle.awaitTerminal())
            assertNull(NovelWorkspaceStore(directory).read("drafts/new.md"))
            assertEquals(before, NovelWorkspaceStore(directory).fileTree())
            assertEquals("正在写的一段正文", NovelWorkspaceTurnOutputs.loadPending(directory, "branch-1").single().content)
        } finally {
            runnerScope.cancel()
        }
    }

    @Test fun providerFailureAfterTwoWritesToANewMaterialDeletesIt() = runTest {
        val directory = NovelWorkspaceProjectRepository(temporary.newFolder()).createBlank("Rollback fixture").projectDirectory
        val before = NovelWorkspaceStore(directory).fileTree()
        val kernel = object : RunKernel {
            override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
                val write = session.tools.first { it.name == "novel_workspace_write" }
                for (content in listOf("First character", "Revised character")) {
                    write.execute(buildJsonObject { put("path", "setting/characters/new.md"); put("content", content) })
                }
                emit(partial(session))
                error("provider interrupted")
            }
        }
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val handle = launcher(runnerScope).launch(request(directory), NovelWorkspaceRuntime(kernel))
            assertTrue(handle.events.toList().last() is NovelWorkspaceRuntime.TurnEvent.Failed)
            assertNull(NovelWorkspaceStore(directory).read("setting/characters/new.md"))
            assertEquals(before, NovelWorkspaceStore(directory).fileTree())
        } finally {
            runnerScope.cancel()
        }
    }
}
