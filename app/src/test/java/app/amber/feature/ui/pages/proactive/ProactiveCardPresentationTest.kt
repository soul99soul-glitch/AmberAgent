package app.amber.feature.ui.pages.proactive

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.toMessageNode
import app.amber.feature.macgateway.ProactiveCard
import app.amber.feature.macgateway.ProactiveEventMeta
import app.amber.feature.ui.pages.chat.ChatTimelineEntry
import app.amber.feature.ui.pages.chat.ChatVirtualItemCache
import app.amber.feature.ui.pages.chat.PostSendTimelineState
import app.amber.feature.ui.pages.chat.buildChatTimelinePlan
import app.amber.feature.ui.pages.chat.buildPostSendTimelineState
import app.amber.feature.ui.pages.chat.lazyContentType
import app.amber.feature.ui.pages.chat.lazyItemKey
import app.amber.feature.ui.pages.chat.resolveCloudMainGatewayIdForProactiveCards
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProactiveCardPresentationTest {

    private fun sampleCard(
        signalId: String = "sig_1",
        eventId: String = "proactive:$signalId",
        seq: Long = 10L,
        receivedAt: Long = 2_000L,
        sourceLabel: String = "",
        category: String = "WATCHTOWER",
        title: String = "Release v2.0",
        text: String = "A new version is available.",
        urgency: String = "normal",
        actions: List<String> = listOf("act", "snooze", "mute"),
        feedbackState: String? = null,
    ): ProactiveCard = ProactiveCard(
        gatewayId = "gw-1",
        deviceId = "dev-1",
        eventId = eventId,
        signalId = signalId,
        seq = seq,
        text = text,
        meta = ProactiveEventMeta(
            signalId = signalId,
            topicKey = "watchtower:repo",
            category = category,
            title = title,
            urgency = urgency,
            sourceLabel = sourceLabel,
            actions = actions,
        ),
        receivedAt = receivedAt,
        feedbackState = feedbackState,
    )

    @Test
    fun mapsActionsStrictlyInOrderAndIgnoresUnknownOrDuplicateActions() {
        val full = ProactiveCardPresentationMapper.map(
            sampleCard(actions = listOf("act", "snooze", "mute")),
        )
        assertEquals(
            listOf(
                ProactiveCardActionKind.ACT,
                ProactiveCardActionKind.SNOOZE,
                ProactiveCardActionKind.MUTE,
            ),
            full.actions,
        )

        val partialAndUnknown = ProactiveCardPresentationMapper.map(
            sampleCard(actions = listOf("MUTE", "future_action_v9", "act", "mute", "  ")),
        )
        assertEquals(
            listOf(ProactiveCardActionKind.MUTE, ProactiveCardActionKind.ACT),
            partialAndUnknown.actions,
        )

        val empty = ProactiveCardPresentationMapper.map(
            sampleCard(actions = emptyList()),
        )
        assertTrue(empty.actions.isEmpty())
    }

    @Test
    fun parsesUrgencySafelyIncludingUnknownFutureValues() {
        val urgent = ProactiveCardPresentationMapper.map(sampleCard(urgency = "urgent"))
        assertEquals(ProactiveCardUrgency.URGENT, urgent.urgency)
        assertTrue(urgent.isUrgent)

        val urgentUpper = ProactiveCardPresentationMapper.map(sampleCard(urgency = " URGENT "))
        assertTrue(urgentUpper.isUrgent)

        val low = ProactiveCardPresentationMapper.map(sampleCard(urgency = "low"))
        assertEquals(ProactiveCardUrgency.LOW, low.urgency)
        assertFalse(low.isUrgent)

        val unknown = ProactiveCardPresentationMapper.map(sampleCard(urgency = "super_cosmic_v9"))
        assertEquals(ProactiveCardUrgency.NORMAL, unknown.urgency)
        assertFalse(unknown.isUrgent)
    }

    @Test
    fun fallsBackToCategoryWhenSourceLabelIsBlank() {
        val explicit = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "GitHub Releases", category = "WATCHTOWER"),
        )
        assertEquals("GitHub Releases", explicit.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.EXPLICIT, explicit.sourceFallback)

        val watchtower = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "   ", category = "WATCHTOWER"),
        )
        assertEquals("Watchtower", watchtower.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.WATCHTOWER, watchtower.sourceFallback)

        val webhook = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "", category = "webhook"),
        )
        assertEquals("Webhook", webhook.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.WEBHOOK, webhook.sourceFallback)

        val pulse = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "", category = "daily_pulse"),
        )
        assertEquals("Daily Pulse", pulse.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.PULSE, pulse.sourceFallback)

        val commitment = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "", category = "commitment"),
        )
        assertEquals("Reminder", commitment.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.COMMITMENT, commitment.sourceFallback)

        val unknownCategory = ProactiveCardPresentationMapper.map(
            sampleCard(sourceLabel = "", category = "custom_feed"),
        )
        assertEquals("custom_feed", unknownCategory.sourceLabel)
        assertEquals(ProactiveCardSourceFallback.RAW_CATEGORY, unknownCategory.sourceFallback)
    }

    @Test
    fun mapsPersistedFeedbackStateToPresentationBadge() {
        assertEquals(
            ProactiveCardFeedbackBadge.REPLIED,
            ProactiveCardPresentationMapper.map(sampleCard(feedbackState = "replied")).feedbackBadge,
        )
        assertEquals(
            ProactiveCardFeedbackBadge.SNOOZED,
            ProactiveCardPresentationMapper.map(sampleCard(feedbackState = "snooze")).feedbackBadge,
        )
        assertEquals(
            ProactiveCardFeedbackBadge.MUTED,
            ProactiveCardPresentationMapper.map(sampleCard(feedbackState = "mute_topic")).feedbackBadge,
        )
        assertNull(
            ProactiveCardPresentationMapper.map(sampleCard(feedbackState = "unknown_state")).feedbackBadge,
        )
    }

    @Test
    fun timelinePlanInterleavesProactiveCardsWithStableKeysAndDoesNotAffectMessageOrSendState() {
        val tz = TimeZone.UTC
        val m0 = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hi")),
            createdAt = Instant.fromEpochMilliseconds(1_000L).toLocalDateTime(tz),
        ).toMessageNode()
        val m1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hello")),
            createdAt = Instant.fromEpochMilliseconds(3_000L).toLocalDateTime(tz),
        ).toMessageNode()
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(m0, m1),
        )
        val cardBetween = sampleCard(signalId = "sig_mid", eventId = "evt_mid", seq = 5L, receivedAt = 2_000L)
        val cardNewest = sampleCard(signalId = "sig_new", eventId = "evt_new", seq = 6L, receivedAt = 4_000L)

        val postSend = buildPostSendTimelineState(conversation, activeGeneration = false)
        assertFalse(postSend.waitingForAssistantContent)
        assertNull(postSend.sentUserMessageIndex)

        val plan = buildChatTimelinePlan(
            conversation = conversation,
            assistant = null,
            showAssistantBubble = true,
            timelineLoading = false,
            hasHistoryLoadingItem = false,
            pendingMessageCount = 0,
            postSendState = PostSendTimelineState(null, null, null, null, false),
            virtualItemCache = ChatVirtualItemCache(),
            // Deliberately pass unsorted to assert receivedAt/seq ordering.
            proactiveCards = listOf(cardNewest, cardBetween),
            timeZone = tz,
        )

        // Reading order: m0(1000), cardBetween(2000), m1(3000), cardNewest(4000), TailCompactMarkers, TimelineTail
        // Reversed (reverseLayout=true): [TimelineTail, TailCompactMarkers, cardNewest, m1, cardBetween, m0]
        assertEquals(6, plan.entries.size)
        assertTrue(plan.entries[0] is ChatTimelineEntry.TimelineTail)
        assertTrue(plan.entries[1] is ChatTimelineEntry.TailCompactMarkers)
        assertEquals(cardNewest, (plan.entries[2] as ChatTimelineEntry.ProactiveCard).card)
        assertEquals(1, (plan.entries[3] as ChatTimelineEntry.Message).messageIndex)
        assertEquals(cardBetween, (plan.entries[4] as ChatTimelineEntry.ProactiveCard).card)
        assertEquals(0, (plan.entries[5] as ChatTimelineEntry.Message).messageIndex)

        // Proactive cards never get a messageIndex and never shift message index lookup accuracy.
        assertNull(plan.entries[2].messageIndex)
        assertNull(plan.entries[4].messageIndex)
        assertEquals(3, plan.lazyIndexForMessage(1))
        assertEquals(5, plan.lazyIndexForMessage(0))
        assertEquals(listOf(null, null, null, 1, null, 0), plan.lazyItemMessageIndexes)

        // Stable key & contentType per frozen contract.
        val newestEntry = plan.entries[2]
        assertEquals(
            "proactive:evt_new",
            newestEntry.lazyItemKey(conversation.id, plan.postSendState, emptyList(), "compact"),
        )
        assertEquals("proactive_card", newestEntry.lazyContentType())
    }

    @Test
    fun cloudMainStageScopeOnlyMergesMatchingCloudMainConversation() {
        val cloudMainId = Uuid.random().toString()
        val localMainId = Uuid.random().toString()
        var peekCalls = 0
        val peek: (String) -> String? = { gw ->
            peekCalls++
            if (gw == "gw-1") cloudMainId else null
        }

        // Exact cloud main conversation for paired gateway -> allowed.
        assertEquals(
            "gw-1",
            resolveCloudMainGatewayIdForProactiveCards(
                conversationId = cloudMainId,
                pairedGatewayId = "gw-1",
                peekCloudMainConversationId = peek,
            ),
        )

        // Local main conversation -> excluded.
        assertNull(
            resolveCloudMainGatewayIdForProactiveCards(
                conversationId = localMainId,
                pairedGatewayId = "gw-1",
                peekCloudMainConversationId = peek,
            ),
        )

        // Unpaired gateway -> excluded without calling peek.
        val beforeUnpaired = peekCalls
        assertNull(
            resolveCloudMainGatewayIdForProactiveCards(
                conversationId = cloudMainId,
                pairedGatewayId = null,
                peekCloudMainConversationId = peek,
            ),
        )
        assertEquals(beforeUnpaired, peekCalls)

        // Different paired gateway without cloud main ID -> excluded.
        assertNull(
            resolveCloudMainGatewayIdForProactiveCards(
                conversationId = cloudMainId,
                pairedGatewayId = "gw-other",
                peekCloudMainConversationId = peek,
            ),
        )
    }
}
