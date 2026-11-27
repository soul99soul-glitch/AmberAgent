package app.amber.feature.ui.pages.chat

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.ui.components.message.resolveAgentPersonaDisplayName
import app.amber.feature.ui.theme.AmberDark
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.AmberSage
import app.amber.feature.ui.theme.AmberSageDark
import app.amber.feature.ui.theme.themePackPaperTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `inline status colors stay noticeably grayer than body ink and provide clear shimmer peak across themes`() {
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
            val colors = resolveInlineStatusShimmerColors(tokens)
            assertTrue(
                "Light status base ink should be noticeably lighter/grayer than body ink",
                colors.baseInk.luminance() > tokens.ink.luminance() + 0.08f,
            )
            assertTrue(
                "Light shimmer peak must be substantially brighter than base ink to be clearly visible",
                colors.peakInk.luminance() > colors.baseInk.luminance() + 0.35f,
            )
            assertTrue(
                "Light shimmer edge should interpolate smoothly between base and peak",
                colors.edgeInk.luminance() > colors.baseInk.luminance() &&
                    colors.edgeInk.luminance() < colors.peakInk.luminance(),
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
            val colors = resolveInlineStatusShimmerColors(tokens)
            assertTrue(
                "Dark status base ink should be softer/dimmer than body ink",
                colors.baseInk.luminance() < tokens.ink.luminance() - 0.10f,
            )
            assertTrue(
                "Dark shimmer peak must be clearly brighter than muted base ink",
                colors.peakInk.luminance() > colors.baseInk.luminance() + 0.30f,
            )
            assertTrue(
                "Dark shimmer edge should interpolate smoothly between base and peak",
                colors.edgeInk.luminance() > colors.baseInk.luminance() &&
                    colors.edgeInk.luminance() < colors.peakInk.luminance(),
            )
        }
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
}
