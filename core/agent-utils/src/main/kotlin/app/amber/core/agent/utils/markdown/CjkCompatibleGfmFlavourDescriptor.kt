package app.amber.core.agent.utils.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.SequentialParserUtil
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

/**
 * Official GFM parsing with the product's existing CJK-adjacent emphasis behavior.
 *
 * JetBrains Markdown follows CommonMark for delimiter runs, which leaves a run such as
 * `中文**strong**中文` literal. Amber's previous dependency treated the adjacent CJK characters as
 * punctuation for flanking checks. Keep that one proven compatibility rule while delegating the
 * actual emphasis node construction and every other parser to the pinned official implementation.
 */
class CjkCompatibleGfmFlavourDescriptor(
    useSafeLinks: Boolean = true,
    absolutizeAnchorLinks: Boolean = false,
    makeHttpsAutoLinks: Boolean = false,
) : GFMFlavourDescriptor(useSafeLinks, absolutizeAnchorLinks, makeHttpsAutoLinks) {
    private val officialParserManager = super.sequentialParserManager

    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> =
            officialParserManager.getParserSequence().dropLast(1) +
                EmphasisLikeParser(
                    CjkCompatibleEmphStrongDelimiterParser(),
                    StrikeThroughDelimiterParser(),
                )
    }
}

private class CjkCompatibleEmphStrongDelimiterParser : DelimiterParser() {
    private val officialProcessor = EmphStrongDelimiterParser()

    override fun scan(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
    ): Int {
        if (iterator.type != MarkdownTokenTypes.EMPH) return 0

        var runLength = 1
        var rightIterator = iterator
        val marker = getType(rightIterator)
        for (ignored in 0 until maxAdvance) {
            if (rightIterator.rawLookup(1) != MarkdownTokenTypes.EMPH) break
            val advanced = rightIterator.advance()
            if (getType(advanced) != marker) break
            rightIterator = advanced
            runLength += 1
        }

        val (canOpen, canClose) = canOpenClose(
            tokens = tokens,
            left = iterator,
            right = rightIterator,
            canSplitText = marker == '*',
        )
        repeat(runLength) { index ->
            delimiters += Info(
                tokenType = MarkdownTokenTypes.EMPH,
                position = iterator.index + index,
                length = runLength,
                canOpen = canOpen,
                canClose = canClose,
                marker = marker,
            )
        }
        return runLength
    }

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) = officialProcessor.process(tokens, iterator, delimiters, result)

    override fun isLeftFlankingRun(
        leftIt: TokensCache.Iterator,
        rightIt: TokensCache.Iterator,
    ): Boolean = !isWhitespace(rightIt, 1) &&
        (!isPunctuation(rightIt, 1) ||
            isWhitespace(leftIt, -1) ||
            isPunctuation(leftIt, -1) ||
            isCjk(leftIt, -1))

    override fun isRightFlankingRun(
        tokens: TokensCache,
        leftIt: TokensCache.Iterator,
        rightIt: TokensCache.Iterator,
    ): Boolean = leftIt.charLookup(-1) != getType(leftIt) &&
        !isWhitespace(leftIt, -1) &&
        (!isPunctuation(leftIt, -1) ||
            isWhitespace(rightIt, 1) ||
            isPunctuation(rightIt, 1) ||
            isCjk(rightIt, 1))

    private fun isCjk(iterator: TokensCache.Iterator, lookup: Int): Boolean {
        val char = iterator.charLookup(lookup)
        return char in '\u2E80'..'\u9FFF' ||
            char in '\uAC00'..'\uD7AF' ||
            char in '\uF900'..'\uFAFF'
    }
}
