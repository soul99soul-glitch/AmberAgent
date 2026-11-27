package app.amber.core.service

import android.app.Application
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.ai.ui.UIMessage
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FavoriteRepository
import app.amber.core.repository.FilesRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.feature.miniapp.ConversationDraftStore
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.OutcomeUnknownPrompt
import app.amber.feature.ui.pages.chat.ChatVM
import app.amber.review.ReflectionFixture
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatTitleRenameRaceTest : DurableRuntimeTestBase() {
    @get:Rule val directory = TemporaryFolder()
    private val models = ViewModelStore()
    private var cleanup: (() -> Unit)? = null

    @Before
    fun pauseMainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun closeOwners() {
        models.clear()
        cleanup?.invoke()
        Dispatchers.resetMain()
    }

    @Test
    fun actualChatRenamePreservesLiveAndFinalDurableTailAcrossSuspendedPersistence() = runTest {
        val gate = SyncRestoreWriteGate()
        val appScope = AppScope()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), appScope, gate)
        val dao = PausedRenameDao(database.conversationDao())
        val repo = ConversationRepository(
            dao, database.messageNodeDao(), database.messageStatsDao(), database.favoriteDao(),
            database, files, MessageFtsManager(database), gate,
        )
        val settings = ReflectionFixture.allocate<SettingsAggregator>()
        ReflectionFixture.set(settings, "_settingsFlow", MutableStateFlow(Settings()))
        val queue = PendingMessageQueue(scope, PendingMessageStore(object : ContextWrapper(context) {
            override fun getFilesDir() = directory.root
        }, Json), gate)
        val store = ConversationStore(repo, settings, scope, files, queue, ChatErrorStore(), gate)
        cleanup = {
            store.cleanup()
            queue.cleanup()
            scope.cancel()
            appScope.cancel()
        }
        val original = Conversation.ofId(Uuid.random()).copy(
            title = "Original title",
            messageNodes = listOf(MessageNode.of(UIMessage.user("Question")), MessageNode.of(UIMessage.assistant("partial"))),
        )
        repo.insertConversation(original)
        store.ensureInitialized(original.id)
        val vm = chatViewModel(original.id, settings, repo, store, files)
        models.put("rename", vm)
        // The rename uses the real VM/service/store path. Initial screen collectors,
        // hydration, and Markdown prewarming are outside this focused race.
        vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.cancel() }
        runCurrent()

        dao.arm()
        vm.updateTitle("User chosen title")
        val rename = vm.viewModelScope.coroutineContext[Job]!!.children.single()
        dao.entered.await()
        val latestNode = original.messageNodes.last().copy(messages = listOf(UIMessage.assistant("LATEST_FINAL_TAIL")))
        val latest = store.getConversationFlow(original.id).value.copy(
            messageNodes = original.messageNodes.dropLast(1) + latestNode,
        )
        store.updateConversation(original.id, latest)
        val checkpointLoadState = store.getTimelineLoadStateFlow(original.id).value
        // A final generation checkpoint captures its snapshot before waiting for the
        // rename's writer gate. It must not roll the title back when it later writes.
        val checkpoint = async(start = CoroutineStart.UNDISPATCHED) {
            store.checkpointConversation(original.id, latest, force = true)
        }
        dao.release.complete(Unit)
        rename.join()
        assertTrue(checkpoint.await())
        val checkpointed = repo.getConversationById(original.id)!!
        // Generation finalization persists the actual live state after the rename,
        // matching the production owner path rather than writing a test-owned snapshot.
        store.persistFinalWindow(original.id, true, latest, checkpointLoadState)

        val live = vm.conversation.value
        val durable = repo.getConversationById(original.id)!!
        // Failure/cancellation does not enter the success-only persistFinalWindow path,
        // so the queued checkpoint must already preserve the durable title on its own.
        assertEquals(
            listOf("User chosen title", "User chosen title", "User chosen title"),
            listOf(checkpointed.title, live.title, durable.title),
        )
        assertEquals(
            listOf(latest.messageNodes, latest.messageNodes, latest.messageNodes),
            listOf(checkpointed.messageNodes, live.messageNodes, durable.messageNodes),
        )
    }

    private fun chatViewModel(
        id: Uuid,
        settings: SettingsAggregator,
        repo: ConversationRepository,
        store: ConversationStore,
        files: FilesManager,
    ): ChatVM {
        val coordinator = ReflectionFixture.allocate<ChatRunCoordinator>()
        ReflectionFixture.set(coordinator, "activeKernelRuns", MutableStateFlow(emptyMap<Uuid, AgentRunId>()))
        val engine = ReflectionFixture.allocate<ConversationContextEngine>()
        ReflectionFixture.set(engine, "compactLifecycleStates", MutableStateFlow(emptyMap<String, CompactLifecycleState>()))
        val service = ReflectionFixture.allocate<ChatService>()
        ReflectionFixture.set(service, "conversationStore", store)
        ReflectionFixture.set(service, "runCoordinator", coordinator)
        ReflectionFixture.set(service, "contextEngine", engine)
        ReflectionFixture.set(service, "hooksFactory", ReflectionFixture.allocate<ChatRunHooksFactory>())
        ReflectionFixture.set(service, "errors", MutableStateFlow(emptyList<ChatError>()))
        ReflectionFixture.set(service, "generationDoneFlow", MutableSharedFlow<Uuid>())
        ReflectionFixture.set(service, "outcomeUnknownFlow", MutableStateFlow(emptyMap<String, List<OutcomeUnknownPrompt>>()))
        return ChatVM(
            id.toString(), context as Application, settings, repo, service,
            ReflectionFixture.allocate<FirebaseAnalytics>(), files,
            FavoriteRepository(database.favoriteDao()),
            ConversationContextRepository(database.conversationCompactDao(), database.conversationContextEventDao(), repo),
            ConversationDraftStore(database.conversationDraftDao(), database.conversationDao()),
            ReflectionFixture.allocate<ChatStartSuggestionGenerator>(),
        )
    }

    private class PausedRenameDao(private val delegate: ConversationDAO) : ConversationDAO by delegate {
        private val armed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fun arm() { armed.set(true) }
        private suspend fun pauseOnce() {
            if (armed.compareAndSet(true, false)) {
                entered.complete(Unit)
                release.await()
            }
        }
        override suspend fun existsById(id: String): Boolean {
            pauseOnce()
            return delegate.existsById(id)
        }
        override suspend fun updateTitle(id: String, title: String, updatedAt: Long) {
            pauseOnce()
            delegate.updateTitle(id, title, updatedAt)
        }
    }
}
