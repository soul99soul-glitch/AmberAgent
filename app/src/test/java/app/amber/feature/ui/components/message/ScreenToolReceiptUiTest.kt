package app.amber.feature.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.theme.AmberDark
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.LocalAmberTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the phone-operation receipt the way chat consumes it: compact inline summary plus the
 * scrollable detail sheet, at default and large font scales. The exported PNGs are the visual
 * evidence for alignment / spacing / truncation; assertions pin the 48dp target and the sections.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h900dp-xxhdpi")
class ScreenToolReceiptUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val heldReceipt = ScreenToolReceiptParser.parse(
        toolName = "screen_run_goal",
        arguments = JsonInstant.parseToJsonElement(
            """{"package_name":"com.tencent.mm","goal":"填写会议议程草稿并准备发送到工作群与项目协作群"}""",
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
              "ocr_reason": "webview_or_canvas_low_text",
              "ocr_block_count": 12,
              "tree_coverage": {"coverage_ratio": 0.08},
              "steps": [
                {"action":"type","target":"n0_10_10_200_80","label":"Type \"明日会议议程：预算与排期\" into 输入框","status":"ok","dispatched":true}
              ],
              "held_sensitive_actions": [
                {"action":"click","target":"n1_200_800_300_860","label":"Click 发送到工作群并把草稿同步到项目协作群","dispatched":false,"reason":"action_not_read_only"}
              ],
              "blocked_sensitive_candidates": [
                {"action":"click","target":"n2_250_800_350_860","label":"确认并发布到全部会话","reason":"sensitive_commit_button_filtered"}
              ],
              "next": "Confirm the held send action manually on the device."
            }
            """.trimIndent(),
        ),
    )!!

    @Test
    fun inlineSummaryKeepsFortyEightDpTargetAndClicks() {
        var clicks = 0
        compose.setContent {
            ReceiptTheme(dark = false, fontScale = 1f) {
                ScreenToolReceiptInlineSummary(
                    receipt = heldReceipt,
                    onClick = { clicks++ },
                    modifier = Modifier.width(380.dp),
                )
            }
        }
        compose.onNodeWithText("待您确认最后一步").assertIsDisplayed()
        compose.onNode(hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, clicks)
        export("screen-receipt-inline-light-380dp.png")
    }

    @Test
    fun inlineSummaryKeepsContentInsideNarrowScreenAtLargeFont() {
        compose.setContent {
            ReceiptTheme(dark = true, fontScale = 1.5f) {
                ScreenToolReceiptInlineSummary(
                    receipt = heldReceipt,
                    onClick = {},
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.onNodeWithText("待您确认最后一步").assertIsDisplayed()
        compose.onNodeWithText("端侧 OCR 12 处").assertIsDisplayed()
        export("screen-receipt-inline-dark-320dp-font1.5.png")
    }

    @Test
    fun detailSheetRendersHeldStepsOcrAndHostReturnSections() {
        val output = listOf(UIMessagePart.Text("receipt"))
        compose.setContent {
            ReceiptTheme(dark = false, fontScale = 1.3f) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ScreenToolReceiptDetailSheetContent(
                        toolName = "screen_run_goal",
                        arguments = JsonInstant.parseToJsonElement(
                            """{"package_name":"com.tencent.mm","goal":"填写会议议程草稿并准备发送"}""",
                        ),
                        content = JsonInstant.parseToJsonElement("{}"),
                        output = output,
                        receipt = heldReceipt,
                    )
                }
            }
        }
        compose.onNodeWithText("已完成前置步骤 · 待您确认敏感动作").assertIsDisplayed()
        compose.onNodeWithText("保留在目标应用前台").assertIsDisplayed()
        compose.onNodeWithText("Click 发送到工作群并把草稿同步到项目协作群").assertIsDisplayed()
        export("screen-receipt-detail-light-font1.3.png")
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "en-w400dp-h900dp-xxhdpi")
    fun inlineAndDetailSheetRenderLocalizedEnglishStrings() {
        val output = listOf(UIMessagePart.Text("receipt"))
        compose.setContent {
            ReceiptTheme(dark = false, fontScale = 1.0f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScreenToolReceiptInlineSummary(
                        receipt = heldReceipt,
                        onClick = {},
                        modifier = Modifier.width(380.dp),
                    )
                    ScreenToolReceiptDetailSheetContent(
                        toolName = "screen_run_goal",
                        arguments = JsonInstant.parseToJsonElement(
                            """{"package_name":"com.tencent.mm","goal":"Draft meeting agenda"}""",
                        ),
                        content = JsonInstant.parseToJsonElement("{}"),
                        output = output,
                        receipt = heldReceipt,
                    )
                }
            }
        }
        compose.onNodeWithText("Confirm final step").assertIsDisplayed()
        compose.onNodeWithText("Pre-steps completed · Confirm sensitive action").assertIsDisplayed()
        compose.onNodeWithText("Kept target app in foreground").assertIsDisplayed()
    }

    @Composable
    private fun ReceiptTheme(
        dark: Boolean,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        val baseDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalAmberTokens provides if (dark) AmberDark else AmberLight,
            LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
        ) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
    }

    private fun export(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File("build/reports/$name").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
