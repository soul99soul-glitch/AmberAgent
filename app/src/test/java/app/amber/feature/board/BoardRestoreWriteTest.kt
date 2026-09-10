package app.amber.feature.board

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.BoardItemEntity
import app.amber.agent.data.db.entity.BoardSignalEntity
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardRestoreWriteTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: BoardRepository
    private lateinit var restoreWriteGate: SyncRestoreWriteGate

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        restoreWriteGate = SyncRestoreWriteGate()
        repository = BoardRepository(
            signalDao = database.boardSignalDao(),
            itemDao = database.boardItemDao(),
            focusRuleDao = database.boardFocusRuleDao(),
            weightDao = database.boardWeightDao(),
            dailyReviewDao = database.dailyReviewDao(),
            restoreWriteGate = restoreWriteGate,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `pre-restore board owner cannot overwrite restored item or signal`() = runBlocking {
        val oldEpoch = restoreWriteGate.currentEpoch()
        val modelReturned = CompletableDeferred<Unit>()
        val persistNow = CompletableDeferred<Unit>()
        val restoredItem = item("restored-item", "restored")
        val restoredSignal = signal("restored-signal", processed = false)
        val staleItem = item(restoredItem.id, "stale")

        supervisorScope {
            val owner = async {
                withContext(SyncRestoreWriteEpoch(oldEpoch)) {
                    modelReturned.complete(Unit)
                    persistNow.await()
                    val itemFailure = runCatching { repository.saveItems(listOf(staleItem)) }
                        .exceptionOrNull()
                    val signalFailure = runCatching {
                        repository.markSignalsProcessed(listOf(restoredSignal.id), now = 20L)
                    }.exceptionOrNull()
                    itemFailure to signalFailure
                }
            }

            modelReturned.await()
            restoreWriteGate.withRestore {
                database.boardItemDao().insert(restoredItem)
                database.boardSignalDao().insert(restoredSignal)
                restoreWriteGate.markDataCommitted()
            }

            persistNow.complete(Unit)
            val (itemFailure, signalFailure) = owner.await()
            assertTrue(itemFailure is SyncRestoreWriteRejectedException)
            assertTrue(signalFailure is SyncRestoreWriteRejectedException)
            assertEquals(restoredItem, database.boardItemDao().getById(restoredItem.id))
            assertEquals(restoredSignal, database.boardSignalDao().getById(restoredSignal.id))
        }
    }

    @Test
    fun `current board owner writes after restore`() = runBlocking {
        val restoredItem = item("restored-item", "restored")
        val restoredSignal = signal("restored-signal", processed = false)
        restoreWriteGate.withRestore {
            database.boardItemDao().insert(restoredItem)
            database.boardSignalDao().insert(restoredSignal)
            restoreWriteGate.markDataCommitted()
        }

        val currentEpoch = restoreWriteGate.currentEpoch()
        withContext(SyncRestoreWriteEpoch(currentEpoch)) {
            repository.saveItems(listOf(item("current-item", "current")))
            repository.markSignalsProcessed(listOf(restoredSignal.id), now = 30L)
        }

        assertEquals("current", database.boardItemDao().getById("current-item")?.title)
        assertTrue(database.boardSignalDao().getById(restoredSignal.id)?.processed == true)
        assertEquals(30L, database.boardSignalDao().getById(restoredSignal.id)?.processedAt)
        assertFalse(database.boardItemDao().getById(restoredItem.id) == null)
    }

    private fun item(id: String, title: String) = BoardItemEntity(
        id = id,
        title = title,
        sourceType = "test",
        sourceRef = id,
        sourceContent = title,
        urgency = "medium",
        category = "action",
        reason = "test",
        suggestion = "test",
        signalTime = 1L,
        boardDate = "2026-09-09",
        createdAt = 1L,
    )

    private fun signal(id: String, processed: Boolean) = BoardSignalEntity(
        id = id,
        sourceType = "test",
        sourceRef = id,
        title = id,
        content = id,
        contentHash = id,
        signalTime = 1L,
        processed = processed,
        processedAt = null,
        createdAt = 1L,
    )
}
