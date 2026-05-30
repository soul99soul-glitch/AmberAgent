package app.amber.feature.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
