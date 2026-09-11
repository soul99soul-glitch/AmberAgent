package app.amber.feature.ui.pages.novel

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
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
import app.amber.feature.novelworkspace.NovelWorkspaceBranches
import app.amber.feature.novelworkspace.NovelWorkspaceFocus
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository
import app.amber.feature.novelworkspace.NovelWorkspaceUnresolvedStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `consistency stop clears both operation flags and can run again`() = runBlocking {
        val scripted = ScriptedKernel(ScriptedKernel.Behavior.Hang)
        val fixture = createFixture(scripted)
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
    fun `consistency partial failure does not publish a report and can run again`() = runBlocking {
        val scripted = ScriptedKernel(
            ScriptedKernel.Behavior.PartialFail,
            ScriptedKernel.Behavior.Hang,
        )
        val fixture = createFixture(scripted)
        val viewModel = fixture.viewModel

        viewModel.runConsistencyCheck()
        awaitState(viewModel) {
            !it.busy && !it.consistencyChecking &&
                it.errorMessage == "provider unavailable" && scripted.calls.get() == 1
        }
        assertEquals(null, viewModel.state.value.consistencyReport)

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
    )

    private suspend fun createFixture(
        scripted: ScriptedKernel,
        unresolved: Boolean = false,
        focus: NovelWorkspaceFocus = NovelWorkspaceFocus(),
        withFork: Boolean = false,
    ): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val repository = NovelWorkspaceProjectRepository(temporary.newFolder("workspace"))
        val project = repository.createBlank("View model fixture")
        val directory = project.projectDirectory
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
                        baseUrl = "https://example.test/v1",
                        models = listOf(model),
                    ),
                ),
            ),
        )
        withTimeout(5_000) {
            settings.settingsFlow.first {
                !it.init && it.chatModelId == model.id &&
                    it.providers.any { provider -> provider.models.any { it.id == model.id } }
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
        val viewModel = NovelMarkdownWorkspaceViewModel(
            projectId = directory.name,
            repository = repository,
            settingsAggregator = settings,
            ghostwriteController = controller,
            turnLauncher = launcher,
            kernel = scripted,
            context = context,
            requestedFocus = focus,
        )
        awaitState(viewModel) {
            !it.loading && it.exists && (!unresolved || it.unresolvedFromOrdinal == 1)
        }
        return Fixture(viewModel)
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
        enum class Behavior { Hang, Fail, PartialFail }

        private val behaviors = initial.toList().also { require(it.isNotEmpty()) }
        val calls = AtomicInteger()
        override fun run(session: GenerationRunSession) = flow<GenerationChunk> {
            when (behaviors.getOrElse(calls.getAndIncrement()) { behaviors.last() }) {
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
