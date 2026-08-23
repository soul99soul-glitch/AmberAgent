package app.amber.feature.ui.components.richtext

import app.amber.feature.ui.components.richtext.tree.MdNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingMarkdownRepairTest {
    @Test
    fun `repairs unfinished strong marker with synthetic suffix`() {
        val result = repairStreamingMarkdownTail("hello **amber")

        assertEquals("hello **amber**", result.text)
        assertEquals("hello **amber".length, result.syntheticSuffixStart)
    }

    @Test
    fun `repairs unfinished inline code with synthetic suffix`() {
        val result = repairStreamingMarkdownTail("run `adb shell")

        assertEquals("run `adb shell`", result.text)
        assertEquals("run `adb shell".length, result.syntheticSuffixStart)
    }

    @Test
    fun `repairs unfinished fenced code without treating fence as source`() {
        val tail = "```kotlin\nval x = 1"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals("```kotlin\nval x = 1\n```", result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `repairs unfinished inline math with synthetic suffix`() {
        val tail = "energy is " + "$" + "mc"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals("energy is " + "$" + "mc" + "$", result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `does not repair valid strong marker pairs`() {
        val tail = "hello **amber**"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals(tail, result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `does not repair adjacent valid strong marker pairs`() {
        val tail = "****"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals(tail, result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `does not repair valid double backtick code span`() {
        val tail = "run ``adb shell`` now"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals(tail, result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `does not repair valid display math pairs`() {
        val tail = "energy is " + "$$" + "mc" + "$$"
        val result = repairStreamingMarkdownTail(tail)

        assertEquals(tail, result.text)
        assertEquals(tail.length, result.syntheticSuffixStart)
    }

    @Test
    fun `live suffix exposes text newer than throttled parse`() {
        val result = streamingLiveSuffixFor(
            renderContent = "hello amber",
            activeBaseOffset = 0,
            parsedPreprocessed = "hello",
            syntheticSuffixStart = "hello".length,
            streaming = true,
        )

        assertEquals(" amber", result.text)
        assertEquals("hello".length, result.sourceOffset)
    }

    @Test
    fun `live suffix ignores synthetic repair suffix`() {
        val parsed = repairStreamingMarkdownTail("hello **amb")
        val result = streamingLiveSuffixFor(
            renderContent = "hello **amber",
            activeBaseOffset = 0,
            parsedPreprocessed = parsed.text,
            syntheticSuffixStart = parsed.syntheticSuffixStart,
            streaming = true,
        )

        assertEquals("er", result.text)
        assertEquals("hello **amb".length, result.sourceOffset)
    }

    @Test
    fun `live suffix stays empty when parsed text no longer matches source`() {
        val result = streamingLiveSuffixFor(
            renderContent = "https://example.com",
            activeBaseOffset = 0,
            parsedPreprocessed = "<https://example.com>",
            syntheticSuffixStart = "<https://example.com>".length,
            streaming = true,
        )

        assertEquals("", result.text)
    }

    @Test
    fun `live suffix stays empty when active base offset is out of range`() {
        val negativeOffset = streamingLiveSuffixFor(
            renderContent = "hello",
            activeBaseOffset = -1,
            parsedPreprocessed = "hello",
            syntheticSuffixStart = "hello".length,
            streaming = true,
        )
        val beyondEndOffset = streamingLiveSuffixFor(
            renderContent = "hello",
            activeBaseOffset = "hello".length + 1,
            parsedPreprocessed = "hello",
            syntheticSuffixStart = "hello".length,
            streaming = true,
        )

        assertEquals("", negativeOffset.text)
        assertEquals("", beyondEndOffset.text)
    }

    @Test
    fun `code fence appends live suffix while parse is throttled`() {
        val suffix = streamingCodeFenceLiveSuffixFor(
            sourceOffsetBase = 0,
            codeContentStartOffset = "```kotlin\n".length,
            codeContentEndOffset = "```kotlin\nval x = 1".length,
            syntheticSuffixStart = "```kotlin\nval x = 1".length,
            liveSuffix = "\nval y = 2",
            liveSuffixSourceOffset = "```kotlin\nval x = 1".length,
        )

        assertEquals("\nval y = 2", suffix)
    }

    @Test
    fun `code fence live suffix hides final fence terminator`() {
        val suffix = streamingCodeFenceLiveSuffixFor(
            sourceOffsetBase = 0,
            codeContentStartOffset = "```kotlin\n".length,
            codeContentEndOffset = "```kotlin\nval x = 1".length,
            syntheticSuffixStart = "```kotlin\nval x = 1".length,
            liveSuffix = "\nval y = 2\n```\n",
            liveSuffixSourceOffset = "```kotlin\nval x = 1".length,
        )

        assertEquals("\nval y = 2", suffix)
    }

    @Test
    fun `parse stabilizes completed top-level blocks structurally`() {
        // multi-block: leading blocks finalize, the last stays active
        val multi = StreamingMarkdownParseCache().parse("alpha\n\nbravo\n\ncharlie")
        assertTrue(multi.stableTopLevelBlocks.isNotEmpty())

        // single still-growing block: nothing finalized yet
        val single = StreamingMarkdownParseCache().parse("alpha")
        assertTrue(single.stableTopLevelBlocks.isEmpty())
    }

    @Test
    fun `finalize complete keeps stable branch and empties active tail`() {
        val content = "alpha\n\nbravo\n\ncharlie"
        val cache = StreamingMarkdownParseCache()
        val streaming = cache.parse(content)
        val streamingStableKeys = streaming.stableTopLevelBlocks.map { it.key }
        val activeTail = streaming.tree.children.single()
        val activeTailKey = "active:${activeTail.type}:${streaming.activeBaseOffset + activeTail.startOffset}:0"

        val finalized = cache.finalizeComplete(content)

        assertEquals(emptyList<MdNode>(), finalized.tree.children)
        assertEquals(
            streamingStableKeys,
            finalized.stableTopLevelBlocks.take(streamingStableKeys.size).map { it.key },
        )
        assertEquals(activeTailKey, finalized.stableTopLevelBlocks.last().key)
        assertEquals(streaming.stableTopLevelBlocks.size + 1, finalized.stableTopLevelBlocks.size)
    }

    @Test
    fun `finalize complete falls back when no stable branch was active`() {
        val content = "alpha"
        val cache = StreamingMarkdownParseCache()
        cache.parse(content)

        val finalized = cache.finalizeComplete(content)

        assertTrue(finalized.stableTopLevelBlocks.isEmpty())
        assertTrue(finalized.tree.children.isNotEmpty())
    }

    @Test
    fun `streaming parse throttle does not sample single value flows`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt").readText()

        assertTrue(source.contains(".transform { triple ->"))
        assertTrue(source.contains("SystemClock.uptimeMillis()"))
        assertFalse(source.contains("flowOf(triple).sample"))
        assertFalse(source.contains(".flatMapLatest { triple ->"))
    }

    @Test
    fun `presentation gate keeps new visible text out of semantic parse until motion delay`() {
        val gate = StreamingMarkdownPresentationGate(initialContent = "", commitDelayNanos = 100)

        val initial = gate.update("hello", inputActive = true, nowNanos = 0)
        val beforeDelay = gate.update("hello", inputActive = true, nowNanos = 99)
        val afterDelay = gate.update("hello", inputActive = true, nowNanos = 100)

        assertEquals("", initial.semanticContent)
        assertTrue(initial.motionActive)
        assertEquals("", beforeDelay.semanticContent)
        assertTrue(beforeDelay.motionActive)
        assertEquals("hello", afterDelay.semanticContent)
        assertFalse(afterDelay.motionActive)
    }

    @Test
    fun `presentation gate drains pending motion before final semantic commit`() {
        val gate = StreamingMarkdownPresentationGate(initialContent = "", commitDelayNanos = 100)

        gate.update("hello", inputActive = true, nowNanos = 0)
        val endedBeforeDelay = gate.update("hello", inputActive = false, nowNanos = 50)
        val endedAfterDelay = gate.update("hello", inputActive = false, nowNanos = 100)

        assertEquals("", endedBeforeDelay.semanticContent)
        assertTrue(endedBeforeDelay.motionActive)
        assertEquals("hello", endedAfterDelay.semanticContent)
        assertFalse(endedAfterDelay.motionActive)
    }

    @Test
    fun `presentation gate commits non streaming replacements immediately`() {
        val gate = StreamingMarkdownPresentationGate(initialContent = "hello", commitDelayNanos = 100)

        val replaced = gate.update("goodbye", inputActive = false, nowNanos = 0)

        assertEquals("goodbye", replaced.semanticContent)
        assertFalse(replaced.motionActive)
    }

    @Test
    fun `streaming markdown reveal keeps vertical motion out of block containers`() {
        val source = repoFile("src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt").readText()

        assertTrue(source.contains("BaselineShift"))
        assertTrue(source.contains("STREAMING_CHAR_REVEAL_LIFT_EM"))
        assertFalse(source.contains("STREAMING_BLOCK_REVEAL_OFFSET_DP"))
        assertFalse(source.contains("translationY"))
    }

    private fun repoFile(pathInAppModule: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var dir = File(userDir).absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/$pathInAppModule")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: dir
        }
        return File(userDir, "app/$pathInAppModule")
    }
}
