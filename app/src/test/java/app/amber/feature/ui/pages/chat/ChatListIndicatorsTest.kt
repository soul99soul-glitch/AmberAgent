package app.amber.feature.ui.pages.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.ReplyDeliveryMode
import app.amber.feature.ui.components.message.AssistantBubbleEntranceAlphaDurationMs
import app.amber.feature.ui.components.message.AssistantBubbleEntranceInitialScale
import app.amber.feature.ui.components.message.AssistantBubbleEntranceMaxStaggerMs
import app.amber.feature.ui.components.message.AssistantBubbleEntranceSlideDistance
import app.amber.feature.ui.components.message.AssistantBubbleEntranceSlideDurationMs
import app.amber.feature.ui.components.message.AssistantBubbleEntranceStaggerStepMs
import app.amber.feature.ui.components.message.computeAssistantBubbleEntranceDelayMs
import app.amber.feature.ui.components.message.resolveAgentPersonaDisplayName
import app.amber.feature.ui.components.message.splitConversationalAssistantBubbles
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.defaultAmberTextStyles
import app.amber.feature.ui.theme.AmberDark
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.AmberSage
import app.amber.feature.ui.theme.AmberSageDark
import app.amber.feature.ui.theme.themePackPaperTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChatListIndicatorsTest {

    @Test
    fun `persona display name uses custom name when non-default and falls back to amber`() {
        assertEquals("赵匡胤", resolveAgentPersonaDisplayName(isDefault = false, displayName = "赵匡胤"))
        assertEquals("amber", resolveAgentPersonaDisplayName(isDefault = true, displayName = "赵匡胤"))
        assertEquals("amber", resolveAgentPersonaDisplayName(isDefault = false, displayName = "   "))
    }

    @Test
    fun `waiting indicator shows persona header before assistant content arrives and hides once assistant renders header`() {
        val userTail = UIMessage.user("听你这么一说，我都馋啦！")
        val emptyAssistantTail = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
        val streamingAssistantTail = UIMessage.assistant("宝宝，赵大哥首选大葱蘸甜面酱！")

        // Pre-first-token post-send waiting state: assistant node not present yet
        assertEquals(
            "赵匡胤",
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = true,
                showModelName = true,
                hasRenderedAssistantHeader = false,
                agentDisplayName = "赵匡胤",
            ),
        )

        // Tail waiting while last node is still USER or empty ASSISTANT -> assistant header hasn't rendered
        assertFalse(
            hasRenderedAssistantMessageHeader(
                lastMessage = userTail,
                hasResolvedModel = true,
                showModelName = true,
            ),
        )
        assertFalse(
            hasRenderedAssistantMessageHeader(
                lastMessage = emptyAssistantTail,
                hasResolvedModel = true,
                showModelName = true,
            ),
        )
        assertEquals(
            "赵匡胤",
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = true,
                showModelName = true,
                hasRenderedAssistantHeader = hasRenderedAssistantMessageHeader(
                    lastMessage = emptyAssistantTail,
                    hasResolvedModel = true,
                    showModelName = true,
                ),
                agentDisplayName = "赵匡胤",
            ),
        )

        // Once assistant node has renderable content and resolved model, ChatMessage owns the header -> no duplicate
        assertTrue(
            hasRenderedAssistantMessageHeader(
                lastMessage = streamingAssistantTail,
                hasResolvedModel = true,
                showModelName = true,
            ),
        )
        assertNull(
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = true,
                showModelName = true,
                hasRenderedAssistantHeader = true,
                agentDisplayName = "赵匡胤",
            ),
        )

        // Respects showModelName=false and non-Personal-Agent mode
        assertNull(
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = true,
                showModelName = false,
                hasRenderedAssistantHeader = false,
                agentDisplayName = "赵匡胤",
            ),
        )
        assertNull(
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = false,
                showModelName = true,
                hasRenderedAssistantHeader = false,
                agentDisplayName = "赵匡胤",
            ),
        )
    }

    @Test
    fun `segmented delivery withholds assistant persona header until a visible block is committed`() {
        val uncommittedDraft = UIMessage.assistant("宝宝，羊肉俺可稀罕！正在写第一段")
        val committedParagraph = UIMessage.assistant("宝宝，羊肉俺可稀罕！\n\n哎，下回干脆拿大饼卷葱爆羊肉")
        val thinkingTurn = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("先想想回答语气"),
                UIMessagePart.Text("未换行首段"),
            ),
        )

        // While streaming in SEGMENTED_BLOCKS with only uncommitted text, ChatMessage renders
        // no visible block yet -> must NOT claim the persona header (prevents vertical jump).
        assertFalse(
            hasRenderedAssistantMessageHeader(
                lastMessage = uncommittedDraft,
                hasResolvedModel = true,
                showModelName = true,
                loading = true,
                replyDeliveryMode = ReplyDeliveryMode.SEGMENTED_BLOCKS,
            ),
        )
        assertEquals(
            "赵匡胤",
            resolveWaitingIndicatorAgentName(
                isPersonalAgent = true,
                showModelName = true,
                hasRenderedAssistantHeader = hasRenderedAssistantMessageHeader(
                    lastMessage = uncommittedDraft,
                    hasResolvedModel = true,
                    showModelName = true,
                    loading = true,
                    replyDeliveryMode = ReplyDeliveryMode.SEGMENTED_BLOCKS,
                ),
                agentDisplayName = "赵匡胤",
            ),
        )

        // Once a paragraph boundary commits (or a reasoning/tool block is present, or streaming ends),
        // ChatMessage owns the persona header and the indicator yields immediately.
        assertTrue(
            hasRenderedAssistantMessageHeader(
                lastMessage = committedParagraph,
                hasResolvedModel = true,
                showModelName = true,
                loading = true,
                replyDeliveryMode = ReplyDeliveryMode.SEGMENTED_BLOCKS,
            ),
        )
        assertTrue(
            hasRenderedAssistantMessageHeader(
                lastMessage = thinkingTurn,
                hasResolvedModel = true,
                showModelName = true,
                loading = true,
                replyDeliveryMode = ReplyDeliveryMode.SEGMENTED_BLOCKS,
            ),
        )
        assertTrue(
            hasRenderedAssistantMessageHeader(
                lastMessage = uncommittedDraft,
                hasResolvedModel = true,
                showModelName = true,
                loading = false,
                replyDeliveryMode = ReplyDeliveryMode.SEGMENTED_BLOCKS,
            ),
        )
        // LIVE_STREAMING still renders immediately on first character.
        assertTrue(
            hasRenderedAssistantMessageHeader(
                lastMessage = uncommittedDraft,
                hasResolvedModel = true,
                showModelName = true,
                loading = true,
                replyDeliveryMode = ReplyDeliveryMode.LIVE_STREAMING,
            ),
        )
    }

    @Test
    fun `inline status colors stay noticeably grayer than body ink and follow active theme accent`() {
        val lightThemes = listOfNotNull(
            AmberLight,
            AmberSage,
            themePackPaperTokens("paper", isDark = false),
            themePackPaperTokens("neutral", isDark = false),
            themePackPaperTokens("white", isDark = false),
            themePackPaperTokens("pi", isDark = false),
            themePackPaperTokens("notion", isDark = false),
        )
        for (tokens in lightThemes) {
            val colors = resolveInlineStatusShimmerColors(tokens, tokens.accent)
            assertTrue(
                "Light status base ink should be noticeably lighter/grayer than body ink",
                colors.baseInk.luminance() > tokens.ink.luminance() + 0.08f,
            )
            assertTrue(
                "Light shimmer peak must be substantially brighter than base ink to be clearly visible",
                colors.peakInk.luminance() > colors.baseInk.luminance() + 0.28f,
            )
        }

        val darkThemes = listOfNotNull(
            AmberDark,
            AmberSageDark,
            themePackPaperTokens("paper", isDark = true),
            themePackPaperTokens("neutral", isDark = true),
            themePackPaperTokens("white", isDark = true),
            themePackPaperTokens("pi", isDark = true),
            themePackPaperTokens("notion", isDark = true),
        )
        for (tokens in darkThemes) {
            val colors = resolveInlineStatusShimmerColors(tokens, tokens.accent)
            assertTrue(
                "Dark status base ink should be softer/dimmer than body ink",
                colors.baseInk.luminance() < tokens.ink.luminance() - 0.10f,
            )
            assertTrue(
                "Dark shimmer peak must be clearly brighter than muted base ink",
                colors.peakInk.luminance() > colors.baseInk.luminance() + 0.25f,
            )
        }

        // Switching theme accent must tint both shimmer shoulders and peak in both light & dark modes.
        val terracotta = AmberAccents.first { it.label == "terracotta" }.hex
        val blue = AmberAccents.first { it.label == "blue" }.hex
        val sage = AmberAccents.first { it.label == "sage-green" }.hex
        for (base in listOf(AmberLight, AmberDark)) {
            val warmShimmer = resolveInlineStatusShimmerColors(base, terracotta)
            val blueShimmer = resolveInlineStatusShimmerColors(base, blue)
            val greenShimmer = resolveInlineStatusShimmerColors(base, sage)
            assertEquals(base.ink3, warmShimmer.baseInk)
            assertEquals(base.ink3, blueShimmer.baseInk)
            assertNotEquals(warmShimmer.edgeInk, blueShimmer.edgeInk)
            assertNotEquals(warmShimmer.peakInk, blueShimmer.peakInk)
            assertTrue(
                "Terracotta shoulder should be warmer (higher red channel) than blue shoulder",
                warmShimmer.edgeInk.red > blueShimmer.edgeInk.red + 0.08f,
            )
            assertTrue(
                "Blue shoulder should carry higher blue channel than terracotta shoulder",
                blueShimmer.edgeInk.blue > warmShimmer.edgeInk.blue + 0.08f,
            )
            assertTrue(
                "Sage shoulder should carry distinct green tint vs terracotta",
                abs(greenShimmer.edgeInk.green - warmShimmer.edgeInk.green) > 0.06f,
            )
        }
    }

    @Test
    fun `timeline turn spacing breathes wider between speaker turns and collapses uncommitted segmented shell`() {
        assertEquals(
            PersonalAgentAssistantToUserSpacing,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = true,
                currentRole = MessageRole.ASSISTANT,
                nextRole = MessageRole.USER,
            ),
        )
        assertEquals(
            PersonalAgentUserToAssistantSpacing,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = true,
                currentRole = MessageRole.USER,
                nextRole = MessageRole.ASSISTANT,
            ),
        )
        // Waiting indicator right below a freshly sent user bubble shares exact User -> Assistant spacing.
        assertEquals(
            PersonalAgentUserToAssistantSpacing,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = true,
                currentRole = MessageRole.USER,
                nextRole = null,
            ),
        )
        // Trailing assistant keeps compact spacing to the tail status indicator.
        assertEquals(
            TimelineItemSpacing,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = true,
                currentRole = MessageRole.ASSISTANT,
                nextRole = null,
            ),
        )
        // Uncommitted segmented assistant shell collapses to 0dp so it cannot nudge the timeline.
        assertEquals(
            0.dp,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = true,
                currentRole = MessageRole.ASSISTANT,
                nextRole = null,
                hasVisibleContent = false,
            ),
        )
        // Non-Personal-Agent mode keeps original compact spacing.
        assertEquals(
            TimelineItemSpacing,
            resolveTimelineMessageBottomSpacing(
                isPersonalAgent = false,
                currentRole = MessageRole.ASSISTANT,
                nextRole = MessageRole.USER,
            ),
        )
        assertTrue(PersonalAgentAssistantToUserSpacing in 18.dp..22.dp)
        assertTrue(PersonalAgentUserToAssistantSpacing in 14.dp..18.dp)
    }

    @Test
    fun `conversational assistant bubble splitter splits natural multi-paragraph chat and preserves structured markdown`() {
        val naturalTwoTurns = """
            宝宝，羊肉俺可稀罕！刚才说的是大饼卷啥，不是给肉排座次。真让赵大哥点一道，俺就点葱爆羊肉。

            哎，下回干脆拿大饼卷葱爆羊肉，葱和肉都齐了。恁可别光顾着吃，卷到第三张也得告诉俺一声嘞。
        """.trimIndent()
        assertEquals(
            listOf(
                "宝宝，羊肉俺可稀罕！刚才说的是大饼卷啥，不是给肉排座次。真让赵大哥点一道，俺就点葱爆羊肉。",
                "哎，下回干脆拿大饼卷葱爆羊肉，葱和肉都齐了。恁可别光顾着吃，卷到第三张也得告诉俺一声嘞。",
            ),
            splitConversationalAssistantBubbles(naturalTwoTurns),
        )

        val singleParagraph = "中嘞，宝宝！到时候恁就发俩字：「卷俩。」"
        assertEquals(listOf(singleParagraph), splitConversationalAssistantBubbles(singleParagraph))

        val withCodeFence = "先看这段代码：\n\n```kotlin\nprintln(\"hi\")\n```"
        assertEquals(listOf(withCodeFence), splitConversationalAssistantBubbles(withCodeFence))

        val withHeading = "## 今日推荐\n\n葱爆羊肉配大饼最香。"
        assertEquals(listOf(withHeading), splitConversationalAssistantBubbles(withHeading))

        val withList = "给你挑了两道：\n\n- 葱爆羊肉\n- 酱牛肉"
        assertEquals(listOf(withList), splitConversationalAssistantBubbles(withList))

        val withQuote = "古人说得好：\n\n> 民以食为天。"
        assertEquals(listOf(withQuote), splitConversationalAssistantBubbles(withQuote))

        val withTable = "对比如下：\n\n| 菜名 | 口味 |\n| --- | --- |"
        assertEquals(listOf(withTable), splitConversationalAssistantBubbles(withTable))

        val tooManyParagraphs = (1..5).joinToString("\n\n") { "第${it}段自然口语。" }
        assertEquals(listOf(tooManyParagraphs), splitConversationalAssistantBubbles(tooManyParagraphs))
    }

    @Test
    fun `shimmer sweep scales to measured text width and moves monotonically left to right`() {
        val shortStart = computeInlineStatusShimmerRange(textWidthPx = 180f, minBandWidthPx = 72f, progress = 0f)
        val shortMid = computeInlineStatusShimmerRange(textWidthPx = 180f, minBandWidthPx = 72f, progress = 0.5f)
        val shortEnd = computeInlineStatusShimmerRange(textWidthPx = 180f, minBandWidthPx = 72f, progress = 1f)

        assertTrue(shortStart.endX <= 0.01f)
        assertTrue(shortMid.startX > shortStart.startX)
        assertTrue(shortMid.startX < 180f && shortMid.endX > 0f)
        assertTrue(shortEnd.startX >= 179.99f)

        val longMid = computeInlineStatusShimmerRange(textWidthPx = 420f, minBandWidthPx = 72f, progress = 0.5f)
        assertTrue(
            "Longer status lines should use a wider shimmer band than short lines",
            longMid.bandWidthPx > shortMid.bandWidthPx,
        )
    }

    @Test
    fun `personal agent suggestion chip stays slim and flat`() {
        assertTrue(PersonalAgentSuggestionChipMinHeight in 27.dp..30.dp)
        assertTrue(PersonalAgentSuggestionChipVerticalPadding <= 5.dp)
        assertTrue(PersonalAgentSuggestionChipHorizontalPadding <= 12.dp)
        assertTrue(PersonalAgentSuggestionChipLineHeight <= 16.sp)
    }

    @Test
    fun `inline status and persona header lock line metrics without font padding jitter`() {
        val type = defaultAmberTextStyles()
        assertEquals(24.dp, InlineStatusRowHeight)
        assertEquals(20.sp, InlineStatusTextLineHeight)

        val statusStyle = resolveInlineStatusTextStyle(type.secondary)
        assertEquals(20.sp, statusStyle.lineHeight)
        assertEquals(false, statusStyle.platformStyle?.paragraphStyle?.includeFontPadding)
        assertEquals(LineHeightStyle.Alignment.Center, statusStyle.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.Both, statusStyle.lineHeightStyle?.trim)

        val headerStyle = resolveAgentPersonaHeaderTextStyle(type.meta)
        assertEquals(false, headerStyle.platformStyle?.paragraphStyle?.includeFontPadding)
        assertEquals(LineHeightStyle.Alignment.Center, headerStyle.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.Both, headerStyle.lineHeightStyle?.trim)
    }

    @Test
    fun `assistant bubble entrance uses gentle decoupled timing and bounded multi-bubble stagger`() {
        assertEquals(420, AssistantBubbleEntranceAlphaDurationMs)
        assertEquals(460, AssistantBubbleEntranceSlideDurationMs)
        assertEquals(12.dp, AssistantBubbleEntranceSlideDistance)
        assertEquals(0.988f, AssistantBubbleEntranceInitialScale, 0.0001f)
        assertEquals(90, AssistantBubbleEntranceStaggerStepMs)
        assertEquals(270, AssistantBubbleEntranceMaxStaggerMs)

        assertEquals(0, computeAssistantBubbleEntranceDelayMs(-1))
        assertEquals(0, computeAssistantBubbleEntranceDelayMs(0))
        assertEquals(90, computeAssistantBubbleEntranceDelayMs(1))
        assertEquals(180, computeAssistantBubbleEntranceDelayMs(2))
        assertEquals(270, computeAssistantBubbleEntranceDelayMs(3))
        assertEquals(270, computeAssistantBubbleEntranceDelayMs(4))
    }
}
