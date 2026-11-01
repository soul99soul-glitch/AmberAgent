package app.amber.agent

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.agent.R
import app.amber.agent.data.db.AppDatabase
import app.amber.feature.runtime.ConversationActivityCenter
import app.amber.feature.runtime.ConversationActivityKind
import app.amber.feature.runtime.ConversationActivityNotice
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.ui.pages.chat.ChatActivityPanelContent
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room activity-center device coverage. Does not touch the user database or launch UI. */
@RunWith(AndroidJUnit4::class)
class ConversationActivityStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun excludePriorityOpenAsReadColdStartAndUpgradeBaseline() = runBlocking {
        val dbName = "amber_s9_activity_${System.nanoTime()}.db"
        fun openDb() = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

        var now = 1_000L
        val firstDb = openDb()
        try {
            val store = RoomRunTerminalStore(firstDb.runTerminalDao(), now = { now })
            val registry = RunOwnershipRegistry()
            val center = ConversationActivityCenter(registry, store, now = { now }) { id -> "title-$id" }

            store.begin("hist-done", "hist", "a")
            now = 1_100L
            store.finish("hist-done", RunTerminalState.COMPLETED)
            assertEquals(5_000L, store.ensureActivityBaseline(5_000L))

            now = 6_000L
            store.begin("wait-run", "conv-wait", "a")
            store.pause("wait-run", RunTerminalState.WAITING_USER, PauseReason.ASK_USER)
            now = 6_100L
            store.begin("fail-run", "conv-fail", "a")
            store.finish("fail-run", RunTerminalState.FAILED)
            now = 6_200L
            store.begin("done-run", "conv-done", "a")
            store.finish("done-run", RunTerminalState.COMPLETED)
            registry.register("a", "conv-run", "live-run", Job())

            val all = center.notices(excluding = null).first()
            assertEquals(
                listOf("conv-wait", "conv-fail", "conv-done", "conv-run"),
                all.map { it.conversationId },
            )
            assertEquals(
                listOf(
                    ConversationActivityKind.AWAITING_USER,
                    ConversationActivityKind.FAILED,
                    ConversationActivityKind.COMPLETED,
                    ConversationActivityKind.RUNNING,
                ),
                all.map { it.kind },
            )
            assertTrue("upgrade must not resurrect history completed", all.none { it.runId == "hist-done" })

            val excluded = center.notices(excluding = "conv-wait").first()
            assertFalse(excluded.any { it.conversationId == "conv-wait" })
            assertEquals("conv-fail", excluded.first().conversationId)

            val opened = all.first { it.conversationId == "conv-done" }
            assertTrue(center.markSeen(opened))
            assertTrue(center.notices(excluding = null).first().none { it.conversationId == "conv-done" })
        } finally {
            firstDb.close()
        }

        val restarted = openDb()
        try {
            val store = RoomRunTerminalStore(restarted.runTerminalDao(), now = { 9_000L })
            val center = ConversationActivityCenter(RunOwnershipRegistry(), store, now = { 9_000L }) { id -> "title-$id" }
            val cold = center.notices(excluding = null).first()
            assertTrue(cold.any { it.conversationId == "conv-wait" && it.kind == ConversationActivityKind.AWAITING_USER })
            assertTrue(cold.any { it.conversationId == "conv-fail" && it.kind == ConversationActivityKind.FAILED })
            assertTrue("open-as-read must survive process-equivalent store restart", cold.none { it.conversationId == "conv-done" })
            assertTrue("historical completed before baseline stays quiet", cold.none { it.runId == "hist-done" })
            assertEquals(5_000L, store.ensureActivityBaseline(12_000L))
        } finally {
            restarted.close()
            context.deleteDatabase(dbName)
        }
    }
}

/**
 * Isolated Compose four-kind panel. Launches a target-package ComponentActivity only
 * after [createEmptyComposeRule] (no Activity at rule start) and requires
 * [ChatDeviceFixtureRunner] so isolated Koin is started.
 * Skips when keyguard is locked instead of waiting on idle sync.
 */
@RunWith(AndroidJUnit4::class)
class ConversationActivityDeviceTest {
    @get:Rule(order = 0)
    val idleTimeout = object : org.junit.rules.TestWatcher() {
        override fun starting(description: org.junit.runner.Description) {
            androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            androidx.test.espresso.IdlingPolicies.setIdlingResourceTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @get:Rule(order = 1)
    val host = ComposeHostRule()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fourKindsRenderWaitingFailedCompletedAndRunning() {
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.assumeS9FixtureApplication()
        val notices = listOf(
            notice("wait-conv", "Wait chat", ConversationActivityKind.AWAITING_USER),
            notice("fail-conv", "Fail chat", ConversationActivityKind.FAILED),
            notice("done-conv", "Done chat", ConversationActivityKind.COMPLETED),
            notice("run-conv", "Run chat", ConversationActivityKind.RUNNING),
        )
        var opened: String? = null
        AmberS9DeviceSupport.withZeroAnimations {
            checkNotNull(host.session).onActivity { activity ->
                activity.setContent {
                    FixtureTheme {
                        Column(Modifier.fillMaxWidth()) {
                            ChatActivityPanelContent(notices = notices) { opened = it.conversationId }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.chat_activity_panel_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.chat_activity_kind_waiting)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.chat_activity_kind_failed)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.chat_activity_kind_completed)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.chat_activity_kind_running)).assertIsDisplayed()
            compose.onNodeWithText("Wait chat").assertIsDisplayed()
            AmberS9DeviceSupport.takeUiScreenshot("activity-panel.png")
            compose.onNodeWithText("Wait chat").performClick()
            compose.runOnIdle { assertEquals("wait-conv", opened) }
            AmberS9DeviceSupport.writeEvidence(
                "activity-panel-click.txt",
                "opened=$opened title=Wait chat kind=waiting\n",
            )
        }
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = 1f),
            LocalAmberTokens provides tokens,
            LocalChatTheme provides tokens.toChatTheme(),
        ) {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(tokens.bg)) { content() }
            }
        }
    }

    private fun notice(
        conversationId: String,
        title: String,
        kind: ConversationActivityKind,
    ) = ConversationActivityNotice(
        conversationId = conversationId,
        runId = "run-$conversationId",
        title = title,
        kind = kind,
        sinceMs = 1L,
        versionMs = 1L,
    )
}
