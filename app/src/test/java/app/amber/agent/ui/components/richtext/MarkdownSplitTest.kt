package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownSplitTest {
    @Test
    fun splitMarkdownScaffoldDelegatesToLegacyRenderer() {
        val source = repoFile("src/main/java/app/amber/feature/ui/components/richtext/MarkdownSplit.kt").readText()
        val splitBody = source.functionBody("MarkdownBlockSplit")

        assertTrue(splitBody.contains("MarkdownBlockLegacy("))
        assertFalse(Regex("\\bMarkdownBlock\\s*\\(").containsMatchIn(splitBody))
    }

    @Test
    fun publicMarkdownBlockKeepsSplitFlagDisabledByDefaultAndFallsBackToLegacy() {
        val perfFlags = repoFile("src/main/java/app/amber/agent/PerfFlags.kt").readText()
        val markdownSource = repoFile("src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt").readText()
        val publicBody = markdownSource.functionBody("MarkdownBlock")

        assertTrue(perfFlags.contains("const val USE_SPLIT_MARKDOWN = false"))
        assertTrue(publicBody.contains("PerfFlags.USE_SPLIT_MARKDOWN"))
        assertTrue(publicBody.contains("MarkdownBlockSplit("))
        assertTrue(publicBody.contains("MarkdownBlockLegacy("))
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }

    private fun String.functionBody(functionName: String): String {
        val start = indexOf("fun $functionName(")
        require(start >= 0) { "Cannot locate function $functionName" }
        val bodyStart = indexOf("\n) {", start).takeIf { it >= 0 }?.plus(3)
            ?: indexOf(") {", start).takeIf { it >= 0 }?.plus(2)
            ?: error("Cannot locate body for $functionName")
        require(bodyStart >= 0) { "Cannot locate body for $functionName" }
        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(bodyStart + 1, index)
                }
            }
        }
        error("Cannot locate end of body for $functionName")
    }
}
