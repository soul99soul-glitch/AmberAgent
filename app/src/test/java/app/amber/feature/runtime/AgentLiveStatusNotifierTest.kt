package app.amber.feature.runtime

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.feature.runtime.AgentNotificationActionReceiver
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.XiaomiSuperIsland
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentLiveStatusNotifierTest {
    private lateinit var context: Application
    private lateinit var manager: NotificationManager
    private lateinit var notifier: AgentLiveStatusNotifier
    private val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(
            NotificationChannel(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                "Live generation",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        notifier = AgentLiveStatusNotifier(context, NotificationApprovalTokenRegistry())
        // Availability is an OEM capability probe. Keep the real serialization/build path,
        // but supply a positive probe result so the test can inspect the actual island extras.
        setIslandAvailability(System.currentTimeMillis() to true)
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        setIslandAvailability(null)
    }

    @Test
    fun `inline reply action allows Android to attach RemoteInput`() {
        notify(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "question-call",
                    toolName = "ask_user",
                    input = """{"questions":[{"question":"Continue?","options":["Yes","No"]}]}""",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )

        val action = notification().actions.single()
        assertEquals(
            AgentNotificationActionReceiver.EXTRA_REPLY_TEXT,
            action.remoteInputs.single().resultKey,
        )
        assertFalse("inline replies require a mutable PendingIntent", action.actionIntent.isImmutable)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(AgentNotificationActionReceiver::class.java.name, intent.component?.className)
        assertEquals(AgentNotificationActionReceiver.ACTION_REPLY_ASK_USER, intent.action)
        assertNotNull(intent.getStringExtra(AgentNotificationActionReceiver.EXTRA_APPROVAL_TOKEN))
    }

    @Test
    fun `approval actions remain immutable and share the one time decision token`() {
        notify(
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "terminal-call",
                    toolName = "terminal_execute",
                    input = """{"command":"echo safe"}""",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )

        val actions = notification().actions
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.actionIntent.isImmutable })
        val tokens = actions.map {
            shadowOf(it.actionIntent).savedIntent
                .getStringExtra(AgentNotificationActionReceiver.EXTRA_APPROVAL_TOKEN)
        }
        assertNotNull(tokens.first())
        assertEquals(tokens.first(), tokens.last())
    }

    @Test
    fun `raw terminal command stays private while unlocked details remain visible`() {
        notifyTerminal("LEAK curl https://private.example", hideSensitive = false)

        val notification = notification()
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("LEAK"))
        assertNotNull(notification.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.publicVersion.visibility)
        assertFalse(
            "public lock screen title must redact command contents",
            notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("LEAK"),
        )
        val island = notification.extras.getString("miui.focus.param")
        assertNotNull("exercise the actual Xiaomi island payload", island)
        assertFalse("island/AOD data must redact command contents", island!!.contains("LEAK"))
    }

    @Test
    fun `hide sensitive also removes command from unlocked title`() {
        notifyTerminal("LEAK curl https://private.example", hideSensitive = true)

        val notification = notification()
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("LEAK"))
        assertFalse(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("LEAK"))
        assertFalse(notification.extras.getString("miui.focus.param").orEmpty().contains("LEAK"))
    }

    private fun notifyTerminal(command: String, hideSensitive: Boolean) = notify(
        parts = emptyList(),
        activity = SandboxActivityUiState(
            toolCallId = "terminal-call",
            toolName = "terminal_execute",
            title = "Terminal",
            status = ToolActivityStatus.RUNNING,
            inputPreview = command,
        ),
        hideSensitive = hideSensitive,
    )

    private fun notify(
        parts: List<UIMessagePart>,
        activity: SandboxActivityUiState? = null,
        hideSensitive: Boolean = false,
    ) {
        notifier.notifyRunning(
            conversationId = conversationId,
            senderName = "Assistant",
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts)),
            activity = activity,
            hideSensitive = hideSensitive,
            launchIntent = { null },
            runId = "notification-test-run",
        )
    }

    private fun notification(): Notification = manager.activeNotifications.single().notification

    private fun setIslandAvailability(value: Pair<Long, Boolean>?) {
        XiaomiSuperIsland::class.java.getDeclaredField("cachedAvailability").apply {
            isAccessible = true
            set(null, value)
        }
    }
}
