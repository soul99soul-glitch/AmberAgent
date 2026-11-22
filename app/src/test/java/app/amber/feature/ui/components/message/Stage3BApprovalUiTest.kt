package app.amber.feature.ui.components.message

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.macgateway.MacGatewayStatus
import app.amber.feature.runtime.AccessibilityApprovalSummaryFormatter
import app.amber.feature.ui.components.ai.CloudDialogueStatusDock
import app.amber.feature.ui.pages.setting.TasksSection
import app.amber.feature.ui.theme.AmberDark
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Smartphone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-xxhdpi")
class Stage3BApprovalUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun TestTheme(dark: Boolean = false, content: @Composable () -> Unit) {
        val tokens = if (dark) AmberDark else AmberLight
        CompositionLocalProvider(LocalAmberTokens provides tokens) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(modifier = Modifier.width(360.dp)) {
                    content()
                }
            }
        }
    }

    @Test
    fun cloudDialogueDockEnforces48dpTouchTargetsInDarkMode() {
        val uiState = CloudDialogueUiState(
            enabled = true,
            activeConversationId = "conv-1",
            selectedFollowupWorkItemId = "wi-1",
            tasks = listOf(
                CloudTaskSession(
                    workItemId = "wi-1",
                    topicId = "topic-1",
                    conversationId = "conv-1",
                    title = "长任务标题：同步项目并检查全部依赖与构建产物",
                    progressText = "正在构建…",
                    active = true,
                ),
                CloudTaskSession(
                    workItemId = "wi-2",
                    topicId = "topic-1",
                    conversationId = "conv-1",
                    title = "待恢复的云端操作",
                    active = false,
                    outcomeUnknown = true,
                    sourceRefs = listOf("src/very/long/path/to/module/Service.kt"),
                ),
            ),
        )
        compose.setContent {
            TestTheme(dark = true) {
                CloudDialogueStatusDock(
                    conversationId = "conv-1",
                    cloudActive = true,
                    uiState = uiState,
                    onSelectFollowupTarget = {},
                    onStopTask = {},
                )
            }
        }
        for (label in listOf("补充中", "停止", "重查", "重试", "放弃")) {
            compose.onNode(hasClickAction() and hasText(label, substring = true))
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun accessibilityL3CardPinsWarningBannerAboveTitleAndRedactsUrlAndInput() {
        val urlTool = UIMessagePart.Tool(
            toolCallId = "tc-url",
            toolName = "screen_open_url",
            input = """{"url":"https://user:pw@checkout.example.com/secret/pay?token=999888"}""",
            approvalState = ToolApprovalState.Pending,
        )
        val urlArgs = JsonInstant.parseToJsonElement(urlTool.input)
        compose.setContent {
            TestTheme(dark = false) {
                val (banner, extra) = rememberAccessibilityApprovalState(urlTool, urlArgs)
                val title = toolDisplayTitle(urlTool.toolName, urlArgs, null)
                ChatToolApprovalCard(
                    title = title,
                    toolName = urlTool.toolName,
                    kindLabel = "屏幕",
                    icon = Lucide.Smartphone,
                    statusLabel = "等待确认",
                    detailsLabel = "查看详情",
                    denyLabel = "拒绝",
                    approveLabel = "批准",
                    onDetails = {},
                    onDeny = {},
                    onApprove = {},
                    riskBanner = banner,
                    highRisk = banner != null,
                    extraContent = extra,
                )
            }
        }
        val bannerNode = compose.onNodeWithText("L3 高风险无障碍代操作", substring = true).assertIsDisplayed()
        val hostNodes = compose.onAllNodesWithText("checkout.example.com", substring = true).assertCountEquals(2)
        val bannerTop = bannerNode.getUnclippedBoundsInRoot().top
        assertTrue(
            "L3 risk banner must render above tool title and summary",
            bannerTop < hostNodes[0].getUnclippedBoundsInRoot().top &&
                bannerTop < hostNodes[1].getUnclippedBoundsInRoot().top,
        )
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("999888", substring = true).assertDoesNotExist()
        compose.onNode(hasClickAction() and hasText("拒绝")).assertHeightIsAtLeast(48.dp)
        compose.onNode(hasClickAction() and hasText("批准")).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun accessibilityInputApprovalShowsOnlyCharacterCount() {
        val inputTool = UIMessagePart.Tool(
            toolCallId = "tc-input",
            toolName = "screen_input_text",
            input = """{"text":"S3cr3tPass"}""",
            metadata = buildJsonObject { put(AccessibilityApprovalSummaryFormatter.METADATA_TARGET_APP_KEY, "com.example.very.long.banking.package.name") },
            approvalState = ToolApprovalState.Pending,
        )
        val inputArgs = JsonInstant.parseToJsonElement(inputTool.input)
        compose.setContent {
            TestTheme(dark = true) {
                val (banner, extra) = rememberAccessibilityApprovalState(inputTool, inputArgs)
                val title = toolDisplayTitle(inputTool.toolName, inputArgs, null)
                ChatToolApprovalCard(
                    title = title,
                    toolName = inputTool.toolName,
                    kindLabel = "屏幕",
                    icon = Lucide.Smartphone,
                    statusLabel = "等待确认",
                    detailsLabel = "查看详情",
                    denyLabel = "拒绝",
                    approveLabel = "批准",
                    onDetails = {},
                    onDeny = {},
                    onApprove = {},
                    riskBanner = banner,
                    highRisk = banner != null,
                    extraContent = extra,
                )
            }
        }
        compose.onNodeWithText("10 个字符", substring = true).assertIsDisplayed()
        compose.onNodeWithText("S3cr3tPass", substring = true).assertDoesNotExist()
        compose.onNodeWithText("L3 高风险无障碍代操作", substring = true).assertIsDisplayed()
    }

    @Test
    fun remoteL3ApprovalPinsWarningAboveLongDiffAndHidesApprove() {
        val longDiff = (1..20).joinToString("\n") { "+ line $it: some_very_long_diff_content_for_layout_check()" }
        val l3Session = MacGatewayStatus.Session(
            key = "s-l3",
            agent = "codex",
            subject = "部署生产数据库迁移脚本",
            origin = "cli",
            host = "MacBook-Pro.local",
            state = "waiting",
            waitReason = "permission",
            updatedAt = 1_700_000_000.0,
            pendingApproval = MacGatewayStatus.PendingApproval(
                toolCallId = "call-l3",
                tool = "shell_exec",
                riskLevel = "L3",
                targetDevice = "MacBook-Pro",
                targetSummary = "rm -rf /var/data/prod && migrate",
                diffPreview = longDiff,
                requiresLocalApproval = true,
            ),
        )
        val l2Session = MacGatewayStatus.Session(
            key = "s-l2",
            agent = "claude",
            subject = "修改本地配置",
            origin = "cli",
            host = "MacBook-Pro.local",
            state = "waiting",
            waitReason = "permission",
            updatedAt = 1_700_000_001.0,
            pendingApproval = MacGatewayStatus.PendingApproval(
                toolCallId = "call-l2",
                tool = "file_write",
                riskLevel = "L2",
                targetDevice = "MacBook-Pro",
                targetSummary = "config/settings.json",
                requiresLocalApproval = false,
            ),
        )
        compose.setContent {
            TestTheme(dark = true) {
                TasksSection(
                    sessions = listOf(l3Session, l2Session),
                    synaraURL = null,
                    busy = false,
                    onMonitoredChange = { _, _ -> },
                    onDecision = { _, _, _ -> },
                )
            }
        }
        val warningNode = compose.onNodeWithText("请在 Mac 上批准").assertIsDisplayed()
        val diffLabelNode = compose.onNodeWithText("只读差异预览").assertIsDisplayed()
        assertTrue(
            "L3 local-approval warning must appear above diff preview",
            warningNode.getUnclippedBoundsInRoot().top < diffLabelNode.getUnclippedBoundsInRoot().top,
        )
        compose.onNodeWithText("批准前需通过生物识别验证").assertIsDisplayed()
    }
}
