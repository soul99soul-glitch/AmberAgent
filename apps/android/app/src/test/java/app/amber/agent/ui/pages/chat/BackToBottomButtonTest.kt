package app.amber.feature.ui.pages.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P8-06：Streaming 回到底部按钮。
 *
 * reverseLayout 改造后"在底部" = firstVisibleItemIndex == 0 且偏移在缓冲内，
 * 不再存在跟随状态机（本测试为源码断言，UI 行为不做仪器测试）。
 */
class BackToBottomButtonTest {

    @Test
    fun `button visible only while streaming and user is not at bottom`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // 可见性 = 自动滚动开启 && 生成中 && 不在底部（reverseLayout 下即
        // firstVisibleItemIndex != 0 或偏移超出钉底缓冲）
        assertTrue(source.contains("backToBottomVisible"))
        assertTrue(source.contains("settings.displaySetting.enableAutoScroll"))
        assertTrue(source.contains("activeGeneration"))
        assertTrue(source.contains("state.firstVisibleItemIndex != 0"))
        assertTrue(source.contains("state.firstVisibleItemScrollOffset > bottomPinBufferPx"))
    }

    @Test
    fun `click scrolls back to list index zero`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // reverseLayout: index 0 即视觉底部，短促平滑滚回后原生跟随自动恢复
        assertTrue(source.contains("state.animateScrollToItem(0)"))
        // 程序触发的滚动不应点亮 MessageJumper 的"刚滚动过"信号
        assertTrue(source.contains("isRecentScroll = false"))
    }

    @Test
    fun `button stacks above error cards in bottom overlay without overlap`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt").readText()

        // 错误卡片与按钮在同一底部 Column 内纵向排列（错误在上、按钮在下）
        val columnStart = source.indexOf("// P8-06/P8-07: 底部叠层")
        val errorsCall = source.indexOf("ErrorCardsDisplay(")
        val buttonCall = source.indexOf("BackToBottomButton(")
        assertTrue("底部叠层应存在", columnStart >= 0)
        assertTrue("错误卡片应在叠层内", errorsCall > columnStart)
        assertTrue("按钮应在错误卡片之后（不重叠）", buttonCall > errorsCall)
        // 按钮不遮挡输入区：位于 innerPadding 盒内（bottomBar 之上）
        assertTrue(source.contains("padding(innerPadding)"))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
