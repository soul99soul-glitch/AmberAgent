package app.amber.feature.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.core.files.FileFolders
import app.amber.core.files.FilesManager
import app.amber.core.infra.AppScope
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.FilesRepository
import app.amber.agent.data.db.fts.MessageFtsManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shared Robolectric + in-memory Room setup for the P1-02/P1-03 durable
 * runtime tests (same DB stack as conversation storage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
abstract class DurableRuntimeTestBase {
    protected lateinit var context: Context
    protected lateinit var database: AppDatabase
    protected lateinit var ledger: RoomToolEffectLedger
    protected lateinit var runTerminalStore: RoomRunTerminalStore

    @Before
    fun setUpRuntime() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // MessageFtsManager needs the fts tables (normally created by the
        // DataSourceModule onOpen callback).
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_fts(
                text TEXT,
                node_id TEXT,
                message_id TEXT,
                conversation_id TEXT,
                title TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_title_fts(
                title TEXT,
                conversation_id TEXT,
                update_at TEXT
            )
            """.trimIndent()
        )
        ledger = RoomToolEffectLedger(
            dao = database.toolEffectDao(),
            runTerminalDao = database.runTerminalDao(),
            json = Json,
        )
        runTerminalStore = RoomRunTerminalStore(dao = database.runTerminalDao())
    }

    @After
    fun tearDownRuntime() {
        database.close()
    }

    protected fun conversationRepository(): ConversationRepository {
        val appScope = AppScope()
        val filesManager = FilesManager(
            context = context,
            repository = FilesRepository(database.managedFileDao()),
            appScope = appScope,
        )
        return ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            messageStatsDAO = database.messageStatsDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            filesManager = filesManager,
            messageFtsManager = MessageFtsManager(database),
        )
    }
}
