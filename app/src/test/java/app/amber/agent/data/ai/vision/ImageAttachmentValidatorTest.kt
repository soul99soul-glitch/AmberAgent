package app.amber.core.ai.vision

import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAttachmentValidatorTest {
    @Test
    fun `unsupported image url should block send`() {
        val status = ImageAttachmentValidator.inspectImage(
            image = UIMessagePart.Image("content://missing-image"),
            settings = Settings(),
        )

        assertEquals(ImageAttachmentStatusKind.BLOCKED, status.kind)
        assertTrue(status.message.contains("图片来源暂不支持"))
    }

    @Test
    fun `more than four images should block send before encoding`() {
        val status = ImageAttachmentValidator.firstBlockingIssue(
            parts = List(5) { UIMessagePart.Image("content://missing-image-$it") },
            settings = Settings(),
        )

        assertEquals(ImageAttachmentStatusKind.BLOCKED, status?.kind)
        assertTrue(status?.message?.contains("一次最多发送 4 张图片") == true)
    }
}
