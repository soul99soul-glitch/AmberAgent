package app.amber.core.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.FavoriteEntity
import app.amber.core.sync.core.SyncRestoreWriteEpoch
import app.amber.core.sync.core.SyncRestoreWriteGate
import app.amber.core.sync.core.SyncRestoreWriteRejectedException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FavoriteRepositoryRestoreGateTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `stale favorite writer is rejected after restore`() = runBlocking {
        val gate = SyncRestoreWriteGate()
        val repository = FavoriteRepository(db.favoriteDao(), gate)
        val imported = favorite("imported")
        db.favoriteDao().upsert(imported)
        val staleEpoch = gate.currentEpoch()

        gate.withRestore { gate.markDataCommitted() }

        val failure = runCatching {
            withContext(SyncRestoreWriteEpoch(staleEpoch)) {
                repository.upsert(favorite("stale"))
            }
        }.exceptionOrNull()

        assertTrue(failure is SyncRestoreWriteRejectedException)
        assertEquals("imported", db.favoriteDao().getByRefKey("node:imported")!!.snapshotJson)
    }

    private fun favorite(value: String) = FavoriteEntity(
        id = "favorite:$value",
        type = "node",
        refKey = "node:$value",
        refJson = "{}",
        snapshotJson = value,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
