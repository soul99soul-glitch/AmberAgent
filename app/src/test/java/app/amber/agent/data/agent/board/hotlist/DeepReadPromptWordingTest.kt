package app.amber.feature.board.hotlist

import app.amber.ai.core.Tool
import app.amber.feature.tools.DeepReadToolDescriptionContext
import app.amber.feature.tools.withDeepReadDescriptionContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class DeepReadPromptWordingTest {
    @Test
    fun promptDoesNotDescribeGlobalOrFirstWriterTimeouts() {
        val source = listOf(
            "app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadAgentRunManager.kt",
            "feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadHiddenAssistantFactory.kt",
            "app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSectionWriterTools.kt",
        ).joinToString("\n") { path -> Files.readString(repoFile(path)) }

        assertFalse(source.contains("底层链路可能约"))
        assertFalse(source.contains("底层单次模型/工具链路"))
        assertFalse(source.contains("PROVIDER_REQUEST_SOFT_TIMEOUT_MS"))
        assertFalse(source.contains("FIRST_WRITER_TARGET_WINDOW_MS"))
        assertFalse(source.contains("PLANNING_COLLECT_RUN_TIMEOUT_MS"))
        assertFalse(source.contains("首个 writer tool 目标"))
        assertFalse(source.contains("约 35 秒"))
    }

    @Test
    fun diagramPromptAllowsSparseCrossNodeRelations() {
        val source = listOf(
            "app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadAgentRunManager.kt",
            "app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSectionWriterTools.kt",
        ).joinToString("\n") { path -> Files.readString(repoFile(path)) }

        assertTrue(source.contains("可保留少量关键跨节点关系"))
        assertFalse(source.contains("只允许相邻边"))
        assertFalse(source.contains("keep flow/causal diagrams to the main chain"))
    }

    @Test
    fun deepReadToolDescriptionsCarryStageBudgetWithoutChangingGlobalSearchText() {
        val context = DeepReadToolDescriptionContext(
            stageLabel = "深度分析",
            writerToolName = "deep_read_write_analysis",
            stageTimeoutSeconds = 150,
        )
        val writer = testTool("deep_read_write_analysis")
            .withDeepReadDescriptionContext(context)
        val search = testTool("search_web")
            .withDeepReadDescriptionContext(context)
        val scrape = testTool("scrape_web")
            .withDeepReadDescriptionContext(context)

        assertTrue(writer.description.contains("本段（深度分析）预算约 150 秒"))
        assertTrue(writer.description.contains("优先调用 deep_read_write_analysis"))
        assertTrue(search.description.contains("本段预算约 150 秒"))
        assertTrue(search.description.contains("只为关键事实缺口"))
        assertTrue(scrape.description.contains("deep_read_write_analysis"))
        assertFalse(writer.description.contains("首个 writer tool 目标"))
        assertFalse(search.description.contains("首个 writer tool 目标"))

        val globalSearchTools = Files.readString(
            repoFile("app/src/main/java/app/amber/core/ai/tools/SearchTools.kt")
        )
        assertFalse(globalSearchTools.contains("本段预算"))
        assertFalse(globalSearchTools.contains("首个 writer tool 目标"))
    }

    @Test
    fun deepReadToolDescriptionsDoNotMentionVerificationTool() {
        val context = DeepReadToolDescriptionContext(
            stageTimeoutSeconds = 60,
        )
        val search = testTool("search_web")
            .withDeepReadDescriptionContext(context)
        val writer = testTool("deep_read_write_overview")
            .withDeepReadDescriptionContext(context)

        assertFalse(search.description.contains("验真"))
        assertFalse(search.description.contains("deep_read_verify_claims"))
        assertFalse(writer.description.contains("deep_read_verify_claims"))
    }

    private fun repoFile(path: String): Path {
        var cursor = Paths.get("").toAbsolutePath()
        while (true) {
            cursor.resolve(path).takeIf(Files::exists)?.let { return it }
            cursor.resolve(path.removePrefix("app/")).takeIf(Files::exists)?.let { return it }
            cursor = cursor.parent ?: error("Could not locate $path from ${Paths.get("").toAbsolutePath()}")
        }
    }

    private fun testTool(name: String) = Tool(
        name = name,
        description = "base description",
        execute = { emptyList() },
    )
}
