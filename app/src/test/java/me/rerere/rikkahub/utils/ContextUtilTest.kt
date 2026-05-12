package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUtilTest {
    @Test
    fun `normalize external url decodes html escaped table href`() {
        assertEquals(
            "https://m.bilibili.com/video/BV1abc?from=hot&spm_id_from=333.337",
            normalizeExternalUrl(" https://m.bilibili.com/video/BV1abc?from=hot&amp;spm_id_from=333.337 "),
        )
    }

    @Test
    fun `normalize external url trims markdown punctuation`() {
        assertEquals(
            "https://www.bilibili.com/video/BV1abc",
            normalizeExternalUrl("<https://www.bilibili.com/video/BV1abc>)"),
        )
    }

    @Test
    fun `normalize external url keeps balanced parentheses`() {
        assertEquals(
            "https://example.com/a_(b)",
            normalizeExternalUrl("https://example.com/a_(b)"),
        )
    }
}
