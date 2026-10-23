package app.amber.feature.ui.pages.sessionhome

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.AmberAgentApp
import app.amber.agent.LAUNCH_START_MODE_PREF
import app.amber.agent.LaunchStartMode
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/** Real Home/VM/Room/ChatService path. Only fresh random UUID fixtures may be deleted. */
@RunWith(AndroidJUnit4::class)
class HomeSessionUndoPersistenceDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun batchUndoDeadlineSearchRecreationAndOwnerCancellation() {
        assumeTrue(context.applicationContext is AmberAgentApp)
        val preferences = context.getSharedPreferences("amber_agent.preferences", Context.MODE_PRIVATE)
        val originalMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
        preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).commit()
        val repo: ConversationRepository = getKoin().get()
        // Optional cleanup after an interrupted device run: explicit UUIDs only,
        // with a second title guard. Never scan or bulk-delete user data.
        val interruptedIds = InstrumentationRegistry.getArguments().getString("cleanup_ids")
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            interruptedIds.forEach { value ->
                val row = repo.getConversationSummaryById(Uuid.parse(value))
                if (row != null) {
                    check(row.title.startsWith("UNDO DEVICE TEST "))
                    repo.deleteConversation(row)
                }
                println("Undo interrupted fixture cleaned id=$value")
            }
        }
        val rows = List(2) {
            Conversation.ofId(Uuid.random()).copy(title = "UNDO DEVICE TEST ${Uuid.random()}", isPinned = true)
        }
        val inserted = mutableListOf<Conversation>()
        var scenario: ActivityScenario<RouteActivity>? = null
        fun exists(row: Conversation) = runBlocking { repo.existsConversationById(row.id) }
        fun swipe(row: Conversation) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(row.title))
            compose.onNodeWithText(row.title).assertIsDisplayed().performTouchInput { swipeLeft() }
            compose.onNodeWithTag("home-delete-undo-action").assertIsDisplayed()
        }
        fun undo() = compose.onNodeWithTag("home-delete-undo-action").performClick()
        try {
            runBlocking {
                rows.forEach { row ->
                    check(!repo.existsConversationById(row.id))
                    repo.insertConversation(row)
                    inserted += row
                    println("Undo fixture inserted id=${row.id}")
                }
            }
            // Shell foregrounding avoids vendor background-launch interception.
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/${RouteActivity::class.java.name}",
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            scenario = ActivityScenario.launch(RouteActivity::class.java)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(rows[0].title).fetchSemanticsNodes().isNotEmpty()
            }
            swipe(rows[0])
            assertTrue("Pending must remain durable", exists(rows[0]))
            val barBounds = compose.onNodeWithTag("home-delete-undo").getUnclippedBoundsInRoot()
            val fabBounds = compose.onNodeWithText(context.getString(R.string.history_page_new_conversation)).getUnclippedBoundsInRoot()
            assertTrue("Undo bar must not cover new conversation FAB", barBounds.top >= fabBounds.bottom)
            capture("pending-fab")
            SystemClock.sleep(2_500)
            swipe(rows[1])
            // Beyond the first window, still within the window restarted by row 2.
            SystemClock.sleep(2_700)
            assertTrue(exists(rows[0])); assertTrue(exists(rows[1]))
            undo()
            println("Undo device checkpoint=batch-window-reset-and-undo")
            rows.forEach { assertTrue(exists(it)) }
            compose.onNodeWithText(rows[0].title).assertIsDisplayed()
            compose.onNodeWithText(rows[1].title).assertIsDisplayed()

            // Swipe the restored row again; search and Activity recreation must
            // retain the original deadline, then delete through the real service.
            swipe(rows[0])
            val started = SystemClock.elapsedRealtime()
            compose.onNode(hasScrollAction()).performScrollToIndex(0)
            compose.onNodeWithContentDescription(context.getString(R.string.history_page_search)).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(rows[0].title)
            compose.onNode(hasText(rows[0].title) and !hasSetTextAction()).assertDoesNotExist()
            capture("pending-search-ime")
            scenario.onActivity {
                it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            // Rotation already recreates the Activity; avoid a second concurrent recreation.
            scenario.onActivity { }
            val timeout = (started + 6_200 - SystemClock.elapsedRealtime()).coerceAtLeast(1)
            compose.waitUntil(timeout) { !exists(rows[0]) }
            assertFalse(exists(rows[0]))
            assertTrue(exists(rows[1]))
            println("Undo device checkpoint=deadline-search-rotation-recreation")
            compose.waitForIdle()

            compose.onNodeWithTag("home-delete-undo-action").assertDoesNotExist()

            // Collapse search, delete only the remaining fixture and clear its VM.
            compose.onNodeWithContentDescription(context.getString(R.string.parity_home_search_clear)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.parity_home_search_cancel)).performClick()
            swipe(rows[1])
            println("Undo device checkpoint=pending-before-owner-clear")
            scenario.close()
            scenario = null
            println("Undo device checkpoint=activity-closed")
            foregroundEmptyHost()
            SystemClock.sleep(5_300)
            assertTrue("Clearing owner must cancel pending deletion", exists(rows[1]))
            println("Undo device checkpoint=owner-cleared-pending-safe")
        } finally {
            scenario?.close()
            foregroundEmptyHost()
            try {
                runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(10_000) {
                        inserted.forEach { row ->
                            // Test-only cleanup of empty fixtures that never generated.
                            // The asserted deadline deletion above uses the production VM/service.
                            if (repo.existsConversationById(row.id)) repo.deleteConversation(row)
                            println("Undo fixture cleaned id=${row.id}")
                        }
                    }
                }
            } finally {
                preferences.edit().apply {
                    if (originalMode == null) remove(LAUNCH_START_MODE_PREF)
                    else putString(LAUNCH_START_MODE_PREF, originalMode)
                }.commit()
            }
        }
    }

    private fun foregroundEmptyHost() {
        // Keep instrumentation schedulable under vendor background freezing,
        // without recreating Home or its cleared ViewModel.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/androidx.activity.ComponentActivity",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            val directory = context.getExternalFilesDir("session-delete-undo-device")!!
            directory.mkdirs()
            java.io.File(directory, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
