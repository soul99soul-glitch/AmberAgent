package app.amber.feature.bubble

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.agent.AppScope
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.service.ChatService
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.tools.ACCESSIBILITY_L3_ACTION_TOOLS
import app.amber.feature.tools.SSH_PROFILE_WRITE_TOOL_NAMES
import app.amber.review.ReflectionFixture
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class AgentTaskBubbleApprovalRouteTest {

    @get:Rule
    val compose = createComposeRule()

    private val conversationUuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val conversationId = conversationUuid.toString()

    private data class ApprovalCall(
        val conversationId: Uuid,
        val toolCallId: String,
        val approved: Boolean,
    )

    private class Harness {
        val startedIntents = mutableListOf<Intent>()
        val approvalCalls = mutableListOf<ApprovalCall>()
        private val recordingContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent?) {
                if (intent != null) startedIntents += intent
            }
        }
        val controller = AgentTaskBubbleController(
            context = recordingContext,
            chatService = ReflectionFixture.allocate<ChatService>(),
            activityStore = AgentToolActivityStore(),
            settingsStore = ReflectionFixture.allocate<SettingsAggregator>(),
            appScope = AppScope(),
            approvalDispatcher = { uuid, toolCallId, approved, _ ->
                approvalCalls += ApprovalCall(uuid, toolCallId, approved)
            },
        )
    }

    private fun pendingState(
        toolName: String,
        toolCallId: String = "call_$toolName",
        requiresInAppApproval: Boolean = AgentBubbleReducer.requiresInAppApproval(toolName),
    ) = AgentBubbleUiState(
        phase = AgentBubblePhase.RUNNING,
        conversationId = conversationId,
        waitingApproval = true,
        waitingAskUser = false,
        requiresInAppApproval = requiresInAppApproval,
        approvalToolCallId = toolCallId,
        approvalToolTitle = toolName,
    )

    private fun assertOpenedConversation(intent: Intent) {
        assertEquals(RouteActivity::class.java.name, intent.component?.className)
        assertEquals(conversationId, intent.getStringExtra("conversationId"))
        assertEquals("conversation", intent.getStringExtra("focus"))
    }

    @Test
    fun `approving every accessibility L3 tool routes to app and never calls handleToolApproval`() {
        assertTrue(ACCESSIBILITY_L3_ACTION_TOOLS.isNotEmpty())
        ACCESSIBILITY_L3_ACTION_TOOLS.forEach { toolName ->
            // Verify both reduced state (requiresInAppApproval=true) and stale state (false)
            // are blocked at the controller entry gate.
            listOf(true, false).forEach { flag ->
                val harness = Harness()
                harness.controller.setUiStateForTest(
                    pendingState(toolName = toolName, requiresInAppApproval = flag),
                )

                harness.controller.decideApproval(approved = true)

                assertTrue(
                    "handleToolApproval must never be invoked for L3 tool $toolName (flag=$flag)",
                    harness.approvalCalls.isEmpty(),
                )
                assertEquals(
                    "openConversation must be invoked once for L3 tool $toolName (flag=$flag)",
                    1,
                    harness.startedIntents.size,
                )
                assertOpenedConversation(harness.startedIntents.single())
            }
        }
    }

    @Test
    fun `approving every ssh profile write tool routes to app and never calls handleToolApproval`() {
        assertTrue(SSH_PROFILE_WRITE_TOOL_NAMES.isNotEmpty())
        SSH_PROFILE_WRITE_TOOL_NAMES.forEach { toolName ->
            val harness = Harness()
            harness.controller.setUiStateForTest(pendingState(toolName = toolName))

            harness.controller.decideApproval(approved = true)

            assertTrue(
                "handleToolApproval must never be invoked for SSH write tool $toolName",
                harness.approvalCalls.isEmpty(),
            )
            assertEquals(1, harness.startedIntents.size)
            assertOpenedConversation(harness.startedIntents.single())
        }
    }

    @Test
    fun `denying accessibility L3 and ssh write tools still calls handleToolApproval with false`() {
        (ACCESSIBILITY_L3_ACTION_TOOLS + SSH_PROFILE_WRITE_TOOL_NAMES).forEach { toolName ->
            val harness = Harness()
            val callId = "call_deny_$toolName"
            harness.controller.setUiStateForTest(pendingState(toolName = toolName, toolCallId = callId))

            harness.controller.decideApproval(approved = false)

            assertEquals(
                listOf(ApprovalCall(conversationUuid, callId, false)),
                harness.approvalCalls,
            )
            assertTrue(
                "openConversation must not be invoked on deny for $toolName",
                harness.startedIntents.isEmpty(),
            )
        }
    }

    @Test
    fun `regular tools can still be approved and denied directly from bubble`() {
        listOf("terminal_execute", "screen_read_ui", "web_fetch").forEach { toolName ->
            val approveHarness = Harness()
            val approveCallId = "call_ok_$toolName"
            approveHarness.controller.setUiStateForTest(
                pendingState(toolName = toolName, toolCallId = approveCallId),
            )
            approveHarness.controller.decideApproval(approved = true)
            assertEquals(
                listOf(ApprovalCall(conversationUuid, approveCallId, true)),
                approveHarness.approvalCalls,
            )
            assertTrue(approveHarness.startedIntents.isEmpty())

            val denyHarness = Harness()
            val denyCallId = "call_no_$toolName"
            denyHarness.controller.setUiStateForTest(
                pendingState(toolName = toolName, toolCallId = denyCallId),
            )
            denyHarness.controller.decideApproval(approved = false)
            assertEquals(
                listOf(ApprovalCall(conversationUuid, denyCallId, false)),
                denyHarness.approvalCalls,
            )
            assertTrue(denyHarness.startedIntents.isEmpty())
        }
    }

    @Test
    fun `bubble UI hides direct approve for L3 tool and shows review in app deny stop`() {
        val context = RuntimeEnvironment.getApplication()
        var approveClicks = 0
        var denyClicks = 0
        var openClicks = 0
        var stopClicks = 0

        compose.setContent {
            MaterialTheme {
                AgentTaskBubbleContent(
                    state = pendingState(toolName = "screen_click"),
                    onApprove = { approveClicks++ },
                    onDeny = { denyClicks++ },
                    onStop = { stopClicks++ },
                    onOpenConversation = { openClicks++ },
                    onDrag = { _, _ -> },
                    onDragEnd = {},
                    onSizeChanged = {},
                )
            }
        }

        // Expand bubble from dot state.
        compose.onNodeWithContentDescription(context.getString(R.string.task_bubble_a11y_label))
            .performClick()

        compose.onNodeWithText(context.getString(R.string.task_bubble_approve)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.task_bubble_open_conversation)).assertDoesNotExist()
        compose.onNodeWithText("Review in app").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.task_bubble_deny)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.task_bubble_stop)).assertIsDisplayed().performClick()

        assertEquals(0, approveClicks)
        assertEquals(1, openClicks)
        assertEquals(1, denyClicks)
        assertEquals(1, stopClicks)
    }

    @Test
    fun `bubble UI still shows direct approve for regular tool`() {
        val context = RuntimeEnvironment.getApplication()
        var approveClicks = 0

        compose.setContent {
            MaterialTheme {
                AgentTaskBubbleContent(
                    state = pendingState(toolName = "terminal_execute"),
                    onApprove = { approveClicks++ },
                    onDeny = {},
                    onStop = {},
                    onOpenConversation = {},
                    onDrag = { _, _ -> },
                    onDragEnd = {},
                    onSizeChanged = {},
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.task_bubble_a11y_label))
            .performClick()

        compose.onNodeWithText("Review in app").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.task_bubble_approve)).assertIsDisplayed().performClick()
        assertEquals(1, approveClicks)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
    fun `bubble UI shows zh in-app approval label for SSH write tool and hides direct approve`() {
        val context = RuntimeEnvironment.getApplication()
        var openClicks = 0

        compose.setContent {
            MaterialTheme {
                AgentTaskBubbleContent(
                    state = pendingState(toolName = SSH_PROFILE_WRITE_TOOL_NAMES.first()),
                    onApprove = {},
                    onDeny = {},
                    onStop = {},
                    onOpenConversation = { openClicks++ },
                    onDrag = { _, _ -> },
                    onDragEnd = {},
                    onSizeChanged = {},
                )
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.task_bubble_a11y_label))
            .performClick()

        compose.onNodeWithText(context.getString(R.string.task_bubble_approve)).assertDoesNotExist()
        compose.onNodeWithText("去 App 审批").assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.task_bubble_deny)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.task_bubble_stop)).assertIsDisplayed()
        assertEquals(1, openClicks)
    }
}
