package app.amber.agent.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.entity.BoardItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardItemDAOTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `generated upsert preserves completed and dismissed lifecycle`() = runBlocking {
        val dao = database.boardItemDao()
        dao.insertAll(
            listOf(
                item(id = "completed", title = "old completed"),
                item(id = "dismissed", title = "old dismissed"),
            )
        )
        dao.markCompleted("completed", completedAt = 20L)
        dao.markDismissed("dismissed", dismissedAt = 30L)

        dao.insertAll(
            listOf(
                item(id = "completed", title = "new completed", reason = "new reason"),
                item(id = "dismissed", title = "new dismissed", reason = "new reason"),
            )
        )

        val completed = dao.getById("completed")!!
        assertEquals("new completed", completed.title)
        assertEquals("new reason", completed.reason)
        assertEquals("completed", completed.status)
        assertEquals(20L, completed.completedAt)
        assertNull(completed.dismissedAt)

        val dismissed = dao.getById("dismissed")!!
        assertEquals("new dismissed", dismissed.title)
        assertEquals("dismissed", dismissed.status)
        assertEquals(30L, dismissed.dismissedAt)
        assertNull(dismissed.completedAt)
    }

    private fun item(id: String, title: String, reason: String = "old reason") = BoardItemEntity(
        id = id,
        title = title,
        sourceType = "notification",
        sourceRef = "ref-$id",
        sourceContent = "content",
        urgency = "medium",
        category = "todo",
        reason = reason,
        suggestion = "suggestion",
        signalTime = 10L,
        boardDate = "2026-09-08",
        createdAt = 10L,
    )
}
