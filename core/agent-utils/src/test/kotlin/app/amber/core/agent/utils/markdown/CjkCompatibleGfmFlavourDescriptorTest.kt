package app.amber.core.agent.utils.markdown

import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkCompatibleGfmFlavourDescriptorTest {
    @Test
    fun preservesCjkStrongWithoutReplacingOfficialGfmParsers() {
        val source = """
            常被称为**"离线优先"**策略

            | A | B |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```

            [link](https://example.com)
        """.trimIndent()
        val flavour = CjkCompatibleGfmFlavourDescriptor(
            makeHttpsAutoLinks = true,
            useSafeLinks = true,
        )
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
        val html = HtmlGenerator(source, tree, flavour).generateHtml()

        assertTrue(html.contains("常被称为<strong>&quot;离线优先&quot;</strong>策略"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<code class=\"language-kotlin\">"))
        assertTrue(html.contains("<a href=\"https://example.com\">link</a>"))
    }
}
