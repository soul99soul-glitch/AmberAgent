package app.amber.core.files

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.core.net.toUri
import app.amber.agent.data.db.AppDatabase
import app.amber.core.infra.AppScope
import app.amber.core.repository.FilesRepository
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FilesManagerRestoreGateTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var appScope: AppScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appScope = AppScope()
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    @Test
    fun `stale upload writer is rejected and staged file is cleaned`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val manager = FilesManager(
            context = context,
            repository = FilesRepository(db.managedFileDao()),
            appScope = appScope,
            restoreWriteGate = gate,
        )
        val uploadDir = context.filesDir.resolve(FileFolders.UPLOAD)
        val before = uploadDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val staleEpoch = gate.currentEpoch()

        gate.withRestore { gate.markDataCommitted() }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(staleEpoch)) {
                manager.saveUploadFromBytes(
                    bytes = byteArrayOf(1, 2, 3),
                    displayName = "stale-upload.txt",
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertEquals(0, db.managedFileDao().listByFolder(FileFolders.UPLOAD).first().size)
        val after = uploadDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals(before, after)
    }

    @Test
    fun `stale attachment cleanup is rejected before physical unlink`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val manager = FilesManager(
            context = context,
            repository = FilesRepository(db.managedFileDao()),
            appScope = appScope,
            restoreWriteGate = gate,
        )
        val managed = manager.saveUploadFromBytes(
            bytes = byteArrayOf(4, 5, 6),
            displayName = "restored-attachment.txt",
        )
        val file = manager.getFile(managed)
        val staleEpoch = gate.currentEpoch()

        gate.withRestore { gate.markDataCommitted() }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(staleEpoch)) {
                manager.deleteChatFilesAndAwait(listOf(file.toUri()))
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertTrue(file.exists())
        assertTrue(db.managedFileDao().getById(managed.id) != null)
    }

    @Test
    fun `stale chat image cleanup is rejected before recursive unlink`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val manager = FilesManager(
            context = context,
            repository = FilesRepository(db.managedFileDao()),
            appScope = appScope,
            restoreWriteGate = gate,
        )
        val conversationId = Uuid.random()
        val image = manager.getChatImagesDir(conversationId).resolve("generated.png")
        image.writeBytes(byteArrayOf(7, 8, 9))
        val staleEpoch = gate.currentEpoch()

        gate.withRestore { gate.markDataCommitted() }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(staleEpoch)) {
                manager.deleteChatImagesDirAndAwait(conversationId)
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertTrue(image.exists())
    }
}
