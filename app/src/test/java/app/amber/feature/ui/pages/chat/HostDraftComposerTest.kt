package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.dao.ConversationDraftDAO
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.feature.miniapp.ConversationDraftStore
import app.amber.feature.ui.hooks.ChatInputState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
class HostDraftComposerTest {
    private lateinit var db: AppDatabase
    private lateinit var store: ConversationDraftStore
    private val conversationId = "host-composer"

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        db.conversationDao().insert(ConversationEntity(conversationId, "assistant", "chat", "[]",
            1, 1, "[]", false))
        store = ConversationDraftStore(db.conversationDraftDao(), db.conversationDao())
    }
    @After fun tearDown() { db.close() }

    @Test fun retainedComposerLoadsDraftOnReturnAndReplacesUnmodifiedEarlierDraft() = runBlocking {
        val input = ChatInputState()
        val owner = HostDraftComposer(conversationId, store, input)
        owner.refresh() // Initial chat entry, no draft.
        store.save(conversationId, "host A", emptyList())
        owner.refresh() // Same retained composer on return from runner.
        assertEquals("host A", input.textContent.text.toString())
        store.save(conversationId, "host B", emptyList())
        owner.refresh()
        assertEquals("host B", input.textContent.text.toString())
    }

    @Test fun handEnteredTextWinsOverHostDraft() = runBlocking {
        val input = ChatInputState()
        val owner = HostDraftComposer(conversationId, store, input)
        store.save(conversationId, "host A", emptyList())
        owner.refresh()
        input.setMessageText("author's edited text")
        store.save(conversationId, "host B", emptyList())
        owner.refresh()
        assertEquals("author's edited text", input.textContent.text.toString())
    }

    @Test fun handTypingDuringSuspendedReadCannotBeOverwritten() = runBlocking {
        val input = ChatInputState()
        store.save(conversationId, "host A", emptyList())
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        val realDao = db.conversationDraftDao()
        val delayedDao = object : ConversationDraftDAO by realDao {
            override suspend fun get(conversationId: String): ConversationDraftEntity? {
                val snapshot = realDao.get(conversationId)
                readStarted.complete(Unit)
                resumeRead.await()
                return snapshot
            }
        }
        val delayedStore = ConversationDraftStore(delayedDao, db.conversationDao())
        val owner = HostDraftComposer(conversationId, delayedStore, input)
        val loading = async { owner.refresh() }
        readStarted.await()
        input.setMessageText("author typed while loading")
        resumeRead.complete(Unit)
        loading.await()
        assertEquals("author typed while loading", input.textContent.text.toString())
    }

    @Test fun acceptedSendConsumesCapturedIdAndDoesNotReloadItBeforeClear() = runBlocking {
        val input = ChatInputState()
        val owner = HostDraftComposer(conversationId, store, input)
        val a = store.save(conversationId, "host A", emptyList())
        owner.refresh()
        val consumed = owner.acceptedSendDraftId()!!
        assertEquals(a.draftId, consumed)
        input.clearInput()
        owner.refresh()
        assertEquals("", input.textContent.text.toString())
        val b = store.save(conversationId, "host B", emptyList())
        owner.consume(consumed)
        assertEquals(b, store.load(conversationId))
        owner.refresh()
        assertEquals("host B", input.textContent.text.toString())
        owner.consume(owner.acceptedSendDraftId()!!)
        assertNull(store.load(conversationId))
    }
}
