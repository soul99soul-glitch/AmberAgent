package app.amber.ai.ui

import app.amber.ai.core.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3+ 风险拦截（Surface C / ai-core 改动半径 17 模块）：
 *
 * `MessageStreamAccumulator.append()` 的 `when (deltaPart)` 之前用
 * `else -> println(...)` 兜底，把 UIMessagePart 的 Video/Audio/Document/MiniApp
 * 四个子类静默丢弃——这在 ai-core 的两条生产流式热路径上发生：
 *   - GenerationHandler.kt:498（主生成路径）
 *   - OpenAIProvider.kt:337（OpenAI 流式）
 * 任何流式返回多模态 part 的 provider 都会丢数据，且编译通过（`else` 满足
 * when-statement 的穷尽性）。
 *
 * 修复：把四个子类显式作为 MutablePart.Static 追加（保留数据，不合并），
 * 并去掉 `else` 让 when 变 exhaustive——新增 UIMessagePart 子类时此处编译失败。
 *
 * 本测试钉死：四种多模态 part 流式到达后必须在 snapshot() 中存活。
 * 若有人把 Static 追加改回 println/丢弃，本测试失败。
 */
class MessageStreamAccumulatorMultimodalTest {

    private fun baseMessages(): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
    )

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = "test",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            ),
        ),
    )

    @Test
    fun multimodalPartsArePreservedAlongsideText() {
        val accumulator = MessageStreamAccumulator(baseMessages(), model = null)
        val text = UIMessagePart.Text("answer")
        val video = UIMessagePart.Video(url = "https://example/v.mp4")
        val audio = UIMessagePart.Audio(url = "https://example/a.mp3")
        val document = UIMessagePart.Document(url = "https://example/r.pdf", fileName = "report.pdf")
        val miniApp = UIMessagePart.MiniApp(appId = "wx123", title = "demo", description = "d")

        accumulator.append(chunk(text, video, audio, document, miniApp))

        val parts = accumulator.snapshot().last().parts
        assertEquals(5, parts.size, "Text 与四种多模态 part 应同时存活")
        assertTrue(parts.any { it is UIMessagePart.Text }, "Text part 丢失")
        assertTrue(parts.any { it is UIMessagePart.Video }, "Video part 被 accumulator 丢弃了（旧的 println else 分支）")
        assertTrue(parts.any { it is UIMessagePart.Audio }, "Audio part 被丢弃")
        assertTrue(parts.any { it is UIMessagePart.Document }, "Document part 丢失")
        assertTrue(parts.any { it is UIMessagePart.MiniApp }, "MiniApp part 被丢弃")
    }
}
