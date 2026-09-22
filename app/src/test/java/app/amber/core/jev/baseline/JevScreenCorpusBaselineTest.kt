package app.amber.core.jev.baseline

import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.jev.JevCalibrationStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11 screen 语料冻结断言：镜像 JevScreenFixtureActivity 的屏面结构，
 * 验证候选生成把危险动作排除在 Jev 判题之外、滚动/输入候选按节点属性出现。
 * 语料文件是冻结契约，改动需要显式更新本文件断言。
 */
class JevScreenCorpusBaselineTest {

    @Serializable
    private data class Corpus(
        val version: Int,
        val packageName: String,
        val screens: List<Screen>,
    )

    @Serializable
    private data class Screen(
        val id: String,
        val nodes: List<NodeDto>,
        val expect: Expect,
    )

    @Serializable
    private data class NodeDto(
        val ref: String,
        val label: String,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val scrollable: Boolean = false,
    )

    @Serializable
    private data class Expect(
        val excludedLabelsContain: List<String> = emptyList(),
        val includedLabelsContain: List<String> = emptyList(),
        val scrollCandidates: Int? = null,
        val scrollCandidatesGreaterThanOrEqual: Int? = null,
        val typeCandidateTexts: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun corpus(): Corpus = json.decodeFromString(
        Corpus.serializer(),
        javaClass.classLoader.getResourceAsStream("jev-corpus/screens.json")!!
            .readBytes().decodeToString(),
    )

    private fun snapshot(corpus: Corpus, screen: Screen) = ScreenSnapshot(
        id = screen.id,
        packageName = corpus.packageName,
        windowId = 1,
        nodes = screen.nodes.map { node ->
            ScreenNode(
                ref = node.ref,
                label = node.label,
                viewId = "",
                className = "android.widget.TextView",
                left = 0, top = 0, right = 0, bottom = 0,
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
                enabled = true,
            )
        },
    )

    private fun runner() = JevScreenGoalRunner(
        JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = JevTransport { JevTransportResponse.Failure("offline") }),
                apiKeyProvider = { null },
            ),
            settingsProvider = { Settings() },
            calibration = JevCalibrationStore.IN_MEMORY,
        ),
        // candidates() 是纯函数，不碰主线程；显式 Unconfined 避开 JVM 测试缺 Main 的问题。
        mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    @Test
    fun corpusVersionIsFrozen() {
        assertEquals(1, corpus().version)
    }

    @Test
    fun screenExpectationsHold() {
        val corpus = corpus()
        val screenRunner = runner()
        corpus.screens.forEach { screen ->
            val candidates = screenRunner.candidates(snapshot(corpus, screen), screen.expect.typeCandidateTexts)
            val labels = candidates.map { it.label }
            screen.expect.excludedLabelsContain.forEach { blocked ->
                assertFalse("screen ${screen.id}: blocked label reached candidates: $blocked",
                    labels.any { it.contains(blocked) })
            }
            screen.expect.includedLabelsContain.forEach { expected ->
                assertTrue("screen ${screen.id}: expected candidate missing: $expected",
                    labels.any { it.contains(expected) })
            }
            val scrollCount = labels.count { it.startsWith("Scroll ") }
            screen.expect.scrollCandidates?.let { assertEquals("screen ${screen.id}", it, scrollCount) }
            screen.expect.scrollCandidatesGreaterThanOrEqual?.let {
                assertTrue("screen ${screen.id}: scroll candidates $scrollCount < $it", scrollCount >= it)
            }
            screen.expect.typeCandidateTexts.forEach { text ->
                val typeHit = candidates.filter { it.action.kind == ScreenActionKind.TYPE }
                assertTrue(
                    "screen ${screen.id}: missing type action carrying '$text'",
                    typeHit.any { it.action.text == text },
                )
            }
            val typeCandidates = candidates.filter { it.action.kind == ScreenActionKind.TYPE }
            assertEquals("screen ${screen.id}", screen.expect.typeCandidateTexts.size, typeCandidates.size)
            // type 目标必须是语料里声明为 editable 的节点，且 action 携带的文本与 hint 一致。
            val editableRefs = screen.nodes.filter { it.editable }.map { it.ref }.toSet()
            typeCandidates.forEach { candidate ->
                assertTrue(
                    "screen ${screen.id}: type target ${candidate.action.ref} is not an editable node",
                    candidate.action.ref in editableRefs,
                )
            }
        }
    }

    @Test
    fun worstScreenStateStaysUnderTransmitBudget() {
        val corpus = corpus()
        val maxChars = corpus.screens.maxOf { screen ->
            screen.nodes.map { it.label }.filter { it.isNotBlank() }.distinct()
                .joinToString("\n").length
        }
        // readableScreen 出站前会截 6000 字符（JevScreenGoalRunner.readableScreen 的
        // inline take(6_000)）；语料最长屏必须远低于该值，state 组装才不依赖截断。
        assertTrue("corpus screen text $maxChars chars is too large", maxChars <= 6_000)
    }
}
