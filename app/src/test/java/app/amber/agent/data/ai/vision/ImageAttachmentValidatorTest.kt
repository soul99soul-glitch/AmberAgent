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
        assertTrue(status.message.contains("image source is not supported"))
    }

    @Test
    fun `more than four images should block send before encoding`() {
        val status = ImageAttachmentValidator.firstBlockingIssue(
            parts = List(5) { UIMessagePart.Image("content://missing-image-$it") },
            settings = Settings(),
        )

        assertEquals(ImageAttachmentStatusKind.BLOCKED, status?.kind)
        assertTrue(status?.message?.contains("At most 4 images") == true)
    }
}
