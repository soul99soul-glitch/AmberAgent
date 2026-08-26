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
 * Production-chain canaries for workspace files and persisted artifacts.
 * They use current production components with Robolectric providing the
 * Android environment; durable generation and recovery invariants live in
 * [RuntimeChainCanaryTest].
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
