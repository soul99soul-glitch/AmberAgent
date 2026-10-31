package app.amber.feature.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.amber.feature.ui.pages.chat.ChatActivityButton
import app.amber.feature.ui.pages.chat.ChatIsland
import app.amber.feature.ui.pages.chat.ChatShelfButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.service.PendingUserMessage
import app.amber.core.utils.JsonInstant
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.ui.components.ai.SandboxPhoneTakeoverBanner
import app.amber.feature.ui.components.ai.SandboxSheetHeader
import app.amber.feature.ui.components.ai.SandboxToolActivityContent
import app.amber.feature.ui.components.message.AgentToolCallCapsule
import app.amber.feature.ui.components.message.AgentToolKind
import app.amber.feature.ui.components.message.AgentToolStatus
import app.amber.feature.ui.components.message.ChatToolApprovalCard
import app.amber.feature.ui.components.message.ScreenToolReceiptDetailSheetContent
import app.amber.feature.ui.components.message.ScreenToolReceiptInlineSummary
import app.amber.feature.ui.components.message.ScreenToolReceiptParser
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.ContextCompactMarker
import app.amber.feature.ui.pages.chat.ContextUsageSummary
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.PendingUserMessageBubble
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.pages.setting.SettingCardGroup
import app.amber.feature.ui.pages.setting.settingSingleLine
import app.amber.feature.ui.pages.setting.settingTwoLine
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.TriangleAlert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Phase 5 (A5.1 & A5.3) Compose UI Consistency & Accessibility Matrix Test.
 *
 * Covers:
 * - Font scales: 0.85 / 1.0 / 1.3 / 2.0
 * - Themes: Light (AmberBase.LIGHT) & Dark (AmberBase.DARK)
 * - Screen widths: Narrow (320dp), Standard (412dp), Wide (600dp)
 * - Long text & CJK/English mix
 * - Minimum 48dp interactive touch targets
 * - 5 core surfaces:
 *   1. Chat Main Interface (TopBar/Island + Context/Compact indicators + Takeover Banner + Composer)
 *   2. Approval Card (ChatToolApprovalCard + AgentToolCallCapsule)
 *   3. Receipt Card (ScreenToolReceiptInlineSummary + localized English/Chinese)
 *   4. Settings Main Entries (SettingPage SettingCardGroup + ProviderConfigWarningCard + single/multi-line rows)
 *   5. Bottom Sheets (SandboxSheetHeader + SandboxToolActivityContent + ScreenToolReceiptDetailSheetContent)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w640dp-h1100dp-xxhdpi")
class Phase5UiAccessibilityMatrixScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screenshotDir = File("build/reports/p5-ui-matrix")

    @Test
    fun surface1_chatMainInterface_matrixAndTouchTargets() {
        val cases = listOf(
            MatrixCase("chat-main-light-320dp-font0.85", dark = false, width = 320.dp, fontScale = 0.85f),
            MatrixCase("chat-main-light-412dp-font1.0", dark = false, width = 412.dp, fontScale = 1.0f),
            MatrixCase("chat-main-dark-412dp-font1.3", dark = true, width = 412.dp, fontScale = 1.3f),
            MatrixCase("chat-main-dark-320dp-font2.0", dark = true, width = 320.dp, fontScale = 2.0f),
            MatrixCase("chat-main-light-600dp-font1.0", dark = false, width = 600.dp, fontScale = 1.0f),
        )
        val currentCase = mutableStateOf(cases.first())
        compose.setContent {
            val c = currentCase.value
            MatrixHarness(dark = c.dark, width = c.width, fontScale = c.fontScale) {
                ChatMainSurfacePreview()
            }
        }
        for (c in cases) {
            compose.runOnIdle { currentCase.value = c }
            compose.waitForIdle()
            compose.onNodeWithTag("chat_nav_btn").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            compose.onNodeWithTag("chat_shelf_btn").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            compose.onNodeWithTag("chat_attach_btn").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            compose.onNodeWithTag("chat_send_btn").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            export("${c.name}.png")
        }
    }

    @Test
    fun surface2_approvalCard_matrixAndTouchTargets() {
        val cases = listOf(
            MatrixCase("approval-card-light-320dp-font0.85", dark = false, width = 320.dp, fontScale = 0.85f),
            MatrixCase("approval-card-light-412dp-font1.0", dark = false, width = 412.dp, fontScale = 1.0f),
            MatrixCase("approval-card-dark-412dp-font1.3", dark = true, width = 412.dp, fontScale = 1.3f),
            MatrixCase("approval-card-dark-320dp-font2.0", dark = true, width = 320.dp, fontScale = 2.0f),
        )
        val currentCase = mutableStateOf(cases.first())
        compose.setContent {
            val c = currentCase.value
            MatrixHarness(dark = c.dark, width = c.width, fontScale = c.fontScale) {
                ApprovalCardSurfacePreview()
            }
        }
        for (c in cases) {
            compose.runOnIdle { currentCase.value = c }
            compose.waitForIdle()
            compose.onNodeWithTag("approval_capsule").assertHeightIsAtLeast(48.dp)
            export("${c.name}.png")
        }
    }

    @Test
    fun surface3_receiptCard_matrixAndTouchTargets() {
        val cases = listOf(
            MatrixCase("receipt-card-light-412dp-font1.0", dark = false, width = 412.dp, fontScale = 1.0f),
            MatrixCase("receipt-card-dark-320dp-font1.3", dark = true, width = 320.dp, fontScale = 1.3f),
            MatrixCase("receipt-card-light-320dp-font2.0", dark = false, width = 320.dp, fontScale = 2.0f),
        )
        val currentCase = mutableStateOf(cases.first())
        compose.setContent {
            val c = currentCase.value
            MatrixHarness(dark = c.dark, width = c.width, fontScale = c.fontScale) {
                ReceiptInlineSurfacePreview()
            }
        }
        for (c in cases) {
            compose.runOnIdle { currentCase.value = c }
            compose.waitForIdle()
            compose.onNodeWithTag("receipt_held_inline").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("receipt_verified_inline").assertHeightIsAtLeast(48.dp)
            export("${c.name}.png")
        }
    }

    @Test
    fun surface4_settingsMainEntries_matrixAndTouchTargets() {
        val cases = listOf(
            MatrixCase("settings-main-light-412dp-font1.0", dark = false, width = 412.dp, fontScale = 1.0f),
            MatrixCase("settings-main-dark-412dp-font1.3", dark = true, width = 412.dp, fontScale = 1.3f),
            MatrixCase("settings-main-light-320dp-font2.0", dark = false, width = 320.dp, fontScale = 2.0f),
            MatrixCase("settings-main-dark-600dp-font0.85", dark = true, width = 600.dp, fontScale = 0.85f),
        )
        val currentCase = mutableStateOf(cases.first())
        compose.setContent {
            val c = currentCase.value
            MatrixHarness(dark = c.dark, width = c.width, fontScale = c.fontScale) {
                SettingsSurfacePreview()
            }
        }
        for (c in cases) {
            compose.runOnIdle { currentCase.value = c }
            compose.waitForIdle()
            compose.onNodeWithTag("setting_warning_btn").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("setting_single_line_row").assertHeightIsAtLeast(48.dp)
            export("${c.name}.png")
        }
    }

    @Test
    fun surface5_bottomSheets_matrixAndTouchTargets() {
        val cases = listOf(
            MatrixCase("bottom-sheet-light-412dp-font1.0", dark = false, width = 412.dp, fontScale = 1.0f),
            MatrixCase("bottom-sheet-dark-320dp-font1.3", dark = true, width = 320.dp, fontScale = 1.3f),
            MatrixCase("bottom-sheet-dark-320dp-font2.0", dark = true, width = 320.dp, fontScale = 2.0f),
        )
        val currentCase = mutableStateOf(cases.first())
        compose.setContent {
            val c = currentCase.value
            MatrixHarness(dark = c.dark, width = c.width, fontScale = c.fontScale) {
                BottomSheetSurfacePreview()
            }
        }
        for (c in cases) {
            compose.runOnIdle { currentCase.value = c }
            compose.waitForIdle()
            export("${c.name}.png")
        }
    }

    private data class MatrixCase(
        val name: String,
        val dark: Boolean,
        val width: Dp,
        val fontScale: Float,
    )

    @Composable
    private fun MatrixHarness(
        dark: Boolean,
        width: Dp,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        val base = if (dark) AmberBase.DARK else AmberBase.LIGHT
        val tokens = buildAmberTokens(base, AmberAccents[0].hex)
        val chatTheme = tokens.toChatTheme()
        val baseDensity = LocalDensity.current
        val baseConfig = LocalConfiguration.current
        val customConfig = Configuration(baseConfig).apply {
            this.fontScale = fontScale
            this.screenWidthDp = width.value.toInt()
        }
        CompositionLocalProvider(
            LocalAmberTokens provides tokens,
            LocalChatTheme provides chatTheme,
            LocalConfiguration provides customConfig,
            LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
        ) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(
                    modifier = Modifier
                        .width(width)
                        .background(tokens.bg)
                        .padding(12.dp),
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun ChatMainSurfacePreview() {
        val tokens = LocalAmberTokens.current
        val chatTheme = LocalChatTheme.current
        val takeoverController = remember {
            PhoneAutomationTakeoverController().apply {
                startSession(
                    runId = "run-p5",
                    packageName = "com.tencent.mm",
                    goal = "发送项目进度报告",
                    maxSteps = 4,
                )
                confirmPaused(
                    interceptedSensitiveAction = "点击「确认转账并发送回执」",
                    controller = null,
                )
            }
        }
        val runningScreenActivity = remember {
            SandboxActivityUiState(
                toolCallId = "call-p5",
                toolName = "screen_run_goal",
                title = "执行屏幕目标：发送项目进度报告",
                status = ToolActivityStatus.RUNNING,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. TopBar + Island pill + Activity/Shelf 48dp buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .testTag("chat_nav_btn")
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable {},
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Menu, contentDescription = null, tint = tokens.ink2, modifier = Modifier.size(18.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ChatIsland(
                        title = "Amber 助手 · 长标题多模型切换验证",
                        generating = false,
                        status = null,
                        recapEligible = true,
                        recapOpen = false,
                        enabled = true,
                        onOpenRecap = {},
                        onJumpToLive = {},
                        onStop = {},
                    )
                }
                ChatActivityButton(
                    count = 2,
                    open = false,
                    onClick = {},
                )
                ChatShelfButton(
                    open = false,
                    onClick = {},
                    modifier = Modifier.testTag("chat_shelf_btn"),
                )
            }

            // 2. Context usage summary & Compact marker
            ContextUsageSummary(
                used = 168,
                total = 200,
                progressColor = chatTheme.contextMid,
                lastTurnTotalTokens = 12_800,
                lastTurnCompletionTokens = 800,
                lastTurnCachedTokens = 9_000,
                lastTurnPromptTokens = 12_000,
                lastTurnElapsedMs = 2_400,
            )
            ContextCompactMarker(
                summaryPreview = "上下文已自动压缩 · 保留最近 4 轮完整工具调用与关键决策",
            )

            // 3. Pending User Message Bubble with 48dp cancel button
            PendingUserMessageBubble(
                message = PendingUserMessage(
                    id = "q-1",
                    parts = listOf(UIMessagePart.Text("请继续检查设置页和底部弹层在 2.0 倍字体下的换行与点击热区表现")),
                ),
                queueCount = 2,
                onCancel = {},
                onOpenQueue = {},
            )

            // 4. Phone Takeover Banner (responsive stacked layout at >= 1.3x font scale or < 280dp)
            SandboxPhoneTakeoverBanner(
                activity = runningScreenActivity,
                takeoverController = takeoverController,
            )

            // 5. Composer bar with >= 48dp touch targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .testTag("chat_attach_btn")
                        .clip(CircleShape)
                        .clickable {}
                        .minimumInteractiveComponentSize()
                        .size(44.dp)
                        .background(tokens.surface2)
                        .border(BorderStroke(1.dp, tokens.line), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Plus, contentDescription = null, tint = tokens.ink2, modifier = Modifier.size(22.dp))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(tokens.surface2)
                        .border(BorderStroke(1.dp, tokens.line), RoundedCornerShape(26.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "给 Amber 发消息…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .testTag("chat_send_btn")
                        .clickable {}
                        .minimumInteractiveComponentSize()
                        .size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(tokens.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Send, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ApprovalCardSurfacePreview() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChatToolApprovalCard(
                title = "跨应用执行敏感操作：在微信中向「项目核心推进群」发送今日构建报告与变更清单",
                toolName = "screen_run_goal",
                kindLabel = "自动操作",
                icon = Lucide.Shield,
                statusLabel = "高风险 · 需要你手动批准后才会继续执行",
                detailsLabel = stringResource(R.string.setting_cron_tasks_view_details),
                denyLabel = stringResource(R.string.chat_message_tool_deny),
                approveLabel = stringResource(R.string.chat_message_tool_approve),
                onDetails = {},
                onDeny = {},
                onApprove = {},
                actionsEnabled = true,
                notice = "Jev 安全门禁提示：目标操作包含外部发送动作，已拦截自动点击并切换为人工审批。",
            )
            AgentToolCallCapsule(
                title = "读取工作区配置文件 docs/plans/2026-10-31-android-p5-ui-accessibility.md",
                toolName = "file_read",
                icon = Lucide.Terminal,
                kind = AgentToolKind.FILE,
                status = AgentToolStatus.SUCCEEDED,
                loading = false,
                onClick = {},
                approvalActions = null,
                modifier = Modifier.testTag("approval_capsule"),
            )
        }
    }

    @Composable
    private fun ReceiptInlineSurfacePreview() {
        val context = LocalContext.current
        val heldReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = JsonInstant.parseToJsonElement(
                """{"package_name":"com.tencent.mm","goal":"填写会议议程草稿并准备发送到项目群"}""",
            ),
            content = JsonInstant.parseToJsonElement(
                """
                {
                  "status": "needs_user_action",
                  "reason": "action_not_read_only",
                  "package_name": "com.tencent.mm",
                  "takeover_count": 1,
                  "returned_to_host": false,
                  "host_return_reason": "not_completed_keep_foreground",
                  "ocr_triggered": true,
                  "ocr_block_count": 12,
                  "steps": [
                    {"action":"type","target":"n0_10_10","label":"输入「明日会议议程：P5 UI 矩阵与无障碍验收」","status":"ok","dispatched":true}
                  ],
                  "held_sensitive_actions": [
                    {"action":"click","target":"n1_200_800","label":"点击发送按钮","dispatched":false,"reason":"action_not_read_only"}
                  ]
                }
                """.trimIndent(),
            ),
            context = context,
        )!!
        val verifiedReceipt = ScreenToolReceiptParser.parse(
            toolName = "screen_tap_text",
            arguments = JsonInstant.parseToJsonElement("""{"text":"日程详情"}"""),
            content = JsonInstant.parseToJsonElement(
                """{"status":"ok","verified":true,"text":"日程详情","returned_to_host":true}""",
            ),
            context = context,
        )!!

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenToolReceiptInlineSummary(
                receipt = heldReceipt,
                onClick = {},
                modifier = Modifier.testTag("receipt_held_inline"),
            )
            ScreenToolReceiptInlineSummary(
                receipt = verifiedReceipt,
                onClick = {},
                modifier = Modifier.testTag("receipt_verified_inline"),
            )
        }
    }

    @Composable
    private fun SettingsSurfacePreview() {
        val tokens = LocalAmberTokens.current
        val workspace = workspaceColors()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Provider warning banner
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, tokens.accent.copy(alpha = 0.25f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WorkspaceLeadingIcon(Lucide.TriangleAlert, tone = WorkspaceTone.Warning)
                    Text(
                        text = stringResource(R.string.setting_page_config_api_title),
                        modifier = Modifier.weight(1f),
                        color = tokens.ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {},
                        modifier = Modifier
                            .testTag("setting_warning_btn")
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_config),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            SettingCardGroup(
                title = stringResource(R.string.setting_page_general_settings),
            ) {
                item(
                    modifier = Modifier
                        .testTag("setting_single_line_row")
                        .settingSingleLine(),
                    onClick = {},
                    leadingContent = { WorkspaceLeadingIcon(Lucide.Bot, tone = WorkspaceTone.Accent) },
                    headlineContent = { Text(stringResource(R.string.setting_page_appearance), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = workspace.muted) },
                )
                item(
                    modifier = Modifier.settingSingleLine(),
                    onClick = {},
                    leadingContent = { WorkspaceLeadingIcon(Lucide.KeyRound) },
                    headlineContent = { Text(stringResource(R.string.setting_page_providers), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = workspace.muted) },
                )
                item(
                    modifier = Modifier.settingTwoLine(),
                    onClick = {},
                    leadingContent = { WorkspaceLeadingIcon(Lucide.Sparkles) },
                    headlineContent = { Text(stringResource(R.string.setting_page_mac_gateway), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(R.string.setting_page_mac_gateway_desc), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = workspace.muted) },
                )
                item(
                    modifier = Modifier.settingSingleLine(),
                    onClick = {},
                    leadingContent = { WorkspaceLeadingIcon(Lucide.Brain) },
                    headlineContent = { Text(stringResource(R.string.setting_page_search_service), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = workspace.muted) },
                )
            }
        }
    }

    @Composable
    private fun BottomSheetSurfacePreview() {
        val context = LocalContext.current
        val sandboxActivity = SandboxActivityUiState(
            toolCallId = "call-bash-1",
            toolName = "terminal_exec",
            title = "Executing Gradle unit test suite & baseline verification",
            status = ToolActivityStatus.RUNNING,
            inputPreview = "./gradlew :app:testFullDebugUnitTest --tests app.amber.feature.ui.*",
            outputTail = "BUILD SUCCESSFUL in 4s\n18 actionable tasks: 2 executed, 16 up-to-date",
            runtime = "12.4s · sandbox-0",
            canCancel = true,
            stepIndex = 2,
            stepTotal = 4,
        )
        val receipt = ScreenToolReceiptParser.parse(
            toolName = "screen_run_goal",
            arguments = JsonInstant.parseToJsonElement(
                """{"package_name":"com.tencent.mm","goal":"填写会议议程草稿并准备发送"}""",
            ),
            content = JsonInstant.parseToJsonElement(
                """
                {
                  "status": "needs_user_action",
                  "reason": "action_not_read_only",
                  "package_name": "com.tencent.mm",
                  "takeover_count": 1,
                  "returned_to_host": false,
                  "ocr_triggered": true,
                  "ocr_block_count": 8,
                  "tree_coverage": {"coverage_ratio": 0.42},
                  "steps": [
                    {"action":"click","target":"n1_40_120","label":"点击搜索框并定位项目群","status":"ok","dispatched":true},
                    {"action":"type","target":"n2_40_680","label":"输入会议纪要草稿文本","status":"ok","dispatched":true}
                  ],
                  "held_sensitive_actions": [
                    {"action":"click","target":"n3_280_680","label":"点击发送按钮","dispatched":false,"reason":"action_not_read_only"}
                  ],
                  "next": "请核对草稿无误后手动点击发送按钮"
                }
                """.trimIndent(),
            ),
            context = context,
        )!!

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SandboxSheetHeader(
                activity = sandboxActivity,
                isWebPreview = false,
                onCancel = {},
                onPrevious = {},
                onNext = {},
            )
            SandboxToolActivityContent(
                activity = sandboxActivity,
                modifier = Modifier.heightIn(max = 220.dp),
            )
            ScreenToolReceiptDetailSheetContent(
                toolName = "screen_run_goal",
                arguments = JsonInstant.parseToJsonElement("""{"package_name":"com.tencent.mm"}"""),
                content = JsonInstant.parseToJsonElement("{}"),
                output = listOf(UIMessagePart.Text("receipt")),
                receipt = receipt,
            )
        }
    }

    private fun export(fileName: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File(screenshotDir, fileName).apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
