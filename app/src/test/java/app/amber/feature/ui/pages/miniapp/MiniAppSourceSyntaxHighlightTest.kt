package app.amber.feature.ui.pages.miniapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MiniAppSourceSyntaxHighlightTest {

    @Test
    fun syntaxHighlightingPreservesSourceAndCursorOffsets() {
        val transformation = MiniAppSyntaxTransformation(
            buildAmberTokens(AmberBase.DARK, Color(0xFFB8623A)),
        )
        val sources = listOf(
            "<div>你好 👋 &amp;</div>",
            "<script>\nconst emoji = \"🐙\";\n// 中文注释\n</script>",
            "<style>\n.card::before { content: '© &lt;'; }\n</style>",
        )

        sources.forEach { source ->
            val transformed = transformation.filter(AnnotatedString(source))
            assertEquals(source, transformed.text.text)
            assertSame(OffsetMapping.Identity, transformed.offsetMapping)
        }
    }
}
