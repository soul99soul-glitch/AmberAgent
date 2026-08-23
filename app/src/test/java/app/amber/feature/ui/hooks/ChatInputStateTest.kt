package app.amber.feature.ui.hooks

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for issue #1: 附件-only 草稿曾被 [ChatInputState.isEmpty] 误判为空，
 * 导致无法发送、且生成中点发送被当作 stop。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatInputStateTest {
    @Test
    fun isEmpty_trueWhenNoTextAndNoAttachments() {
        assertTrue(ChatInputState().isEmpty())
    }

    @Test
    fun isEmpty_falseWhenTextOnly() {
        val state = ChatInputState()
        state.setMessageText("hello")
        assertFalse(state.isEmpty())
    }

    @Test
    fun isEmpty_falseWhenAttachmentOnly() {
        val state = ChatInputState()
        state.addImages(listOf(Uri.parse("file:///tmp/a.png")))
        assertFalse(state.isEmpty())
    }

    @Test
    fun isEmpty_backToTrueAfterClear() {
        val state = ChatInputState()
        state.addImages(listOf(Uri.parse("file:///tmp/a.png")))
        state.clearInput()
        assertTrue(state.isEmpty())
    }
}
