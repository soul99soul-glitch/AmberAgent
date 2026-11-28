package app.amber.feature.ui.components.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xxhdpi")
class CompactToolRunCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun makeTool(
        id: String,
        toolName: String,
        inputJson: String = "{}",
        outputJson: String? = """{"ok":true}""",
        approvalState: ToolApprovalState = ToolApprovalState.Approved,
    ): ThinkingStep.ToolStep = ThinkingStep.ToolStep(
        UIMessagePart.Tool(
            toolCallId = id,
            toolName = toolName,
            input = inputJson,
            output = if (outputJson != null) listOf(UIMessagePart.Text(outputJson)) else emptyList(),
            approvalState = approvalState,
            metadata = JsonObject(emptyMap()),
        ),
    )

    @Test
    fun shouldUseCompactCardAndReceiptFilterRulesMatchSpec() {
        val step1 = makeTool("t1", "screen_status")
        val step2 = makeTool("t2", "screen_apps")
        val reasoningOnly = listOf(ThinkingStep.ReasoningStep(UIMessagePart.Reasoning("思考一下")))

        assertFalse(shouldUseCompactToolRunCard(reasoningOnly, isPersonalAgentStage = true))
        assertTrue(shouldUseCompactToolRunCard(listOf(step1), isPersonalAgentStage = true))
        assertFalse(shouldUseCompactToolRunCard(listOf(step1), isPersonalAgentStage = false))
        assertTrue(shouldUseCompactToolRunCard(listOf(step1, step2), isPersonalAgentStage = false))

        // Ordinary read-only screen tools with verified success suppress redundant inline boxes.
        val statusReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_status",
            arguments = JsonInstant.parseToJsonElement("{}"),
            content = JsonInstant.parseToJsonElement("""{"status":"ok","steps":[{"action":"screen_status","status":"ok","dispatched":true}]}"""),
        )
        assertFalse(shouldShowInlineScreenToolReceipt("screen_status", statusReceipt))
        assertFalse(shouldShowInlineScreenToolReceipt("screen_apps", statusReceipt))
        assertFalse(shouldShowInlineScreenToolReceipt("screen_add_app", statusReceipt))
        assertFalse(shouldShowInlineScreenToolReceipt("screen_read_ui", statusReceipt))

        // Multi-step execution tools and failed/held receipts still show inline summary.
        val goalReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = JsonInstant.parseToJsonElement("""{"package_name":"com.xingin.xhs","goal":"刷小红书"}"""),
            content = JsonInstant.parseToJsonElement("""{"status":"completed","goal_verified":true,"steps":[{"action":"click","status":"ok","dispatched":true}]}"""),
        )
        assertTrue(shouldShowInlineScreenToolReceipt("screen_run_goal", goalReceipt))

        val failedStatusReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_status",
            arguments = JsonInstant.parseToJsonElement("{}"),
            content = JsonInstant.parseToJsonElement("""{"status":"error","error":"accessibility_disabled"}"""),
        )
        assertTrue(shouldShowInlineScreenToolReceipt("screen_status", failedStatusReceipt))
    }

    @Test
    fun compactToolRunCard_refreshesInPlace_surfacesPendingApprovalOutside_andExpandsOnTap() {
        var steps by mutableStateOf(
            listOf(
                makeTool("t1", "screen_status", outputJson = null),
            ),
        )
        var loading by mutableStateOf(true)
        var approvedToolId: String? = null

        compose.setContent {
            CompositionLocalProvider(LocalAmberTokens provides AmberLight) {
                MaterialTheme {
                    CompactToolRunCard(
                        toolSteps = steps,
                        loading = loading,
                        onToolApproval = { id, approved, _ ->
                            if (approved) approvedToolId = id
                        },
                    )
                }
            }
        }

        // 1) Step 1 running: single collapsed card shows "第 1 步", history list stays collapsed.
        compose.onNodeWithTag("compact-tool-run-card").assertIsDisplayed()
        compose.onNodeWithTag("compact-tool-run-card-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("第 1 步", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("compact-tool-run-card-steps").assertDoesNotExist()

        // 2) Progress to step 2 (in-place refresh) and step 3 pending approval (surfaced outside fold).
        compose.runOnIdle {
            steps = listOf(
                makeTool("t1", "screen_status", outputJson = """{"status":"ok"}"""),
                makeTool("t2", "screen_apps", outputJson = """{"status":"ok"}"""),
                makeTool(
                    id = "t3",
                    toolName = "screen_add_app",
                    inputJson = """{"package_name":"com.xingin.xhs"}""",
                    outputJson = null,
                    approvalState = ToolApprovalState.Pending,
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("compact-tool-run-card").assertIsDisplayed()
        compose.onNodeWithText("待授权 · 第 3 步", useUnmergedTree = true).assertIsDisplayed()
        // Pending approval card is rendered standalone outside the collapsed card and actionable.
        compose.onNodeWithTag("compact-tool-run-standalone-steps").assertIsDisplayed()
        compose.onNodeWithText("批准").assertIsDisplayed().performClick()
        assertEquals("t3", approvedToolId)

        // 3) After approval completes, all 3 steps settle into single collapsed summary; tap header expands.
        compose.runOnIdle {
            loading = false
            steps = listOf(
                makeTool("t1", "screen_status", outputJson = """{"status":"ok"}"""),
                makeTool("t2", "screen_apps", outputJson = """{"status":"ok"}"""),
                makeTool(
                    id = "t3",
                    toolName = "screen_add_app",
                    inputJson = """{"package_name":"com.xingin.xhs"}""",
                    outputJson = """{"status":"ok"}""",
                    approvalState = ToolApprovalState.Approved,
                ),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithTag("compact-tool-run-standalone-steps").assertDoesNotExist()
        compose.onNodeWithText("已完成 · 3 步", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("compact-tool-run-card-steps").assertDoesNotExist()

        compose.onNodeWithTag("compact-tool-run-card-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("compact-tool-run-card-steps").assertIsDisplayed()
    }
}
