package app.amber.feature.ui.components.message

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Jev 复核暂停自动批准时，审批卡在状态行下方显示原因；无原因时布局不变。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
class ChatToolApprovalCardNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noticeRendersBelowStatusRow() {
        compose.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.testTag("cards").width(380.dp).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    card(notice = "自动批准已暂停：破坏性、超出任务范围")
                    card(notice = null)
                }
            }
        }
        compose.onNodeWithText("自动批准已暂停：破坏性、超出任务范围").assertIsDisplayed()
        // 目视检查用：渲染结果导出到构建目录。
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(android.graphics.Canvas(bitmap)) }
        File("build/reports/jev-approval-card.png").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @androidx.compose.runtime.Composable
    private fun card(notice: String?) {
        ChatToolApprovalCard(
            title = "执行终端命令",
            toolName = "terminal_execute",
            kindLabel = "终端",
            icon = Lucide.Terminal,
            statusLabel = "等待批准",
            detailsLabel = "查看详情",
            denyLabel = "拒绝",
            approveLabel = "批准",
            onDetails = {},
            onDeny = {},
            onApprove = {},
            notice = notice,
        )
    }
}
