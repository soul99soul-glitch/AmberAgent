package app.amber.feature.modelcouncil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * PR2 — Prompt template tests for Council Room.
 *
 * The prompts are pure functions of (room, participant, optional reference) so
 * they can be exercised with plain JUnit. Coverage focuses on:
 * - mode-specific wording (Explore / Debate / Synthesize)
 * - guest-to-guest reference graph rendered into prompts
 * - host synthesis filtering out host/user messages
 */
class CouncilRoomPromptsTest {

    private val conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val hostAssistantId = Uuid.parse("22222222-2222-2222-2222-222222222222")
    private val guestModelId = Uuid.parse("33333333-3333-3333-3333-333333333333")

    private val host = CouncilParticipant(
        id = COUNCIL_ROOM_HOST_ID,
        name = "Host",
        role = "host",
        kind = CouncilParticipantKind.HOST,
    )

    private val guestAlpha = CouncilParticipant(
        id = "alpha",
        name = "Alpha",
        role = "支持者",
        kind = CouncilParticipantKind.GUEST,
        modelId = guestModelId,
        status = CouncilParticipantStatus.INVITED,
    )

    private val guestBeta = CouncilParticipant(
        id = "beta",
        name = "Beta",
        role = "反对者",
        kind = CouncilParticipantKind.GUEST,
        modelId = guestModelId,
        status = CouncilParticipantStatus.INVITED,
    )

    private fun baseRoom(
        mode: CouncilRoomMode = CouncilRoomMode.EXPLORE,
        messages: List<CouncilMessage> = emptyList(),
    ): CouncilRoom = CouncilRoom(
        id = conversationId.toString(),
        conversationId = conversationId,
        hostAssistantId = hostAssistantId,
        objective = "Should we ship the new feature?",
        context = "Engineering team is split on timing.",
        mode = mode,
        status = when (mode) {
            CouncilRoomMode.EXPLORE -> CouncilRoomStatus.EXPLORING
            CouncilRoomMode.DEBATE -> CouncilRoomStatus.DEBATING
            CouncilRoomMode.SYNTHESIZE -> CouncilRoomStatus.FINALIZING
        },
        participants = listOf(host, guestAlpha, guestBeta),
        messages = messages,
        createdAtMs = 1_000L,
    )

    private fun message(
        id: String,
        authorId: String,
        authorName: String,
        role: String,
        text: String,
        mode: CouncilRoomMode = CouncilRoomMode.EXPLORE,
        round: Int = 1,
        replyToMessageId: String? = null,
        continuesFromMessageId: String? = null,
        status: CouncilMessageStatus = CouncilMessageStatus.COMPLETED,
        error: String = "",
    ): CouncilMessage = CouncilMessage(
        id = id,
        authorId = authorId,
        authorName = authorName,
        role = role,
        round = round,
        mode = mode,
        text = text,
        createdAtMs = 2_000L,
        replyToMessageId = replyToMessageId,
        continuesFromMessageId = continuesFromMessageId,
        status = status,
        error = error,
    )

    @Test
    fun `hostSystemPrompt includes objective context mode and participants`() {
        val room = baseRoom()
        val prompt = CouncilRoomPrompts.hostSystemPrompt(room)
        assertTrue("objective missing", prompt.contains(room.objective))
        assertTrue("context missing", prompt.contains(room.context))
        assertTrue("mode missing", prompt.contains("发散（Explore）"))
        assertTrue("Alpha missing", prompt.contains("Alpha（支持者）"))
        assertTrue("Beta missing", prompt.contains("Beta（反对者）"))
        assertTrue("host duty missing", prompt.contains("促动者"))
    }

    @Test
    fun `guestSystemPrompt includes guest name role and mode guidance`() {
        val room = baseRoom(mode = CouncilRoomMode.DEBATE)
        val prompt = CouncilRoomPrompts.guestSystemPrompt(room, guestAlpha)
        assertTrue("guest name missing", prompt.contains("Alpha"))
        assertTrue("role missing", prompt.contains("支持者"))
        assertTrue("debate guidance missing", prompt.contains("对抗优先"))
        assertTrue("boundary missing", prompt.contains("你没有工具"))
    }

    @Test
    fun `exploreOpening asks for breadth and does not reference others`() {
        val room = baseRoom()
        val prompt = CouncilRoomPrompts.exploreOpening(room, guestAlpha)
        assertTrue("objective missing", prompt.contains(room.objective))
        assertTrue("context missing", prompt.contains(room.context))
        assertTrue("breadth instruction missing", prompt.contains("idea"))
        assertTrue("no-ref instruction missing", prompt.contains("不要引用或反驳"))
    }

    @Test
    fun `exploreResponse includes prior message summary blocks`() {
        val prior = message(
            id = "m1",
            authorId = "beta",
            authorName = "Beta",
            role = "反对者",
            text = "Risk of shipping too early.",
        )
        val room = baseRoom(messages = listOf(prior))
        val prompt = CouncilRoomPrompts.exploreResponse(room, guestAlpha, listOf(prior))
        assertTrue("prior text missing", prompt.contains(prior.text))
        assertTrue("prior author missing", prompt.contains("Beta"))
        assertTrue("continue instruction missing", prompt.contains("延续某个信号"))
    }

    @Test
    fun `debateOpening asks for claim and evidence`() {
        val room = baseRoom(mode = CouncilRoomMode.DEBATE)
        val prompt = CouncilRoomPrompts.debateOpening(room, guestAlpha)
        assertTrue("claim missing", prompt.contains("claim"))
        assertTrue("evidence missing", prompt.contains("evidence"))
        assertTrue("round-1 independent stance missing", prompt.contains("独立表态"))
    }

    @Test
    fun `debateResponse references the target message author`() {
        val prior = message(
            id = "m1",
            authorId = "beta",
            authorName = "Beta",
            role = "反对者",
            text = "The API contract is unstable.",
            mode = CouncilRoomMode.DEBATE,
        )
        val room = baseRoom(mode = CouncilRoomMode.DEBATE, messages = listOf(prior))
        val prompt = CouncilRoomPrompts.debateResponse(room, guestAlpha, listOf(prior), prior)
        assertTrue("reference author missing", prompt.contains("Beta 的发言"))
        assertTrue("claim/counterpoint cue missing", prompt.contains("claim/counterpoint/risk/evidence"))
    }

    @Test
    fun `debateResponse without reference asks to revise position`() {
        val room = baseRoom(mode = CouncilRoomMode.DEBATE)
        val prompt = CouncilRoomPrompts.debateResponse(room, guestAlpha, emptyList(), null)
        assertTrue("revise/defend instruction missing", prompt.contains("修订或捍卫"))
        assertFalse("should not name a reference author", prompt.contains("针对"))
    }

    @Test
    fun `debateFinalPosition includes own prior messages`() {
        val ownPrior = message(
            id = "m1",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = "We have enough test coverage.",
            mode = CouncilRoomMode.DEBATE,
        )
        val room = baseRoom(mode = CouncilRoomMode.DEBATE, messages = listOf(ownPrior))
        val prompt = CouncilRoomPrompts.debateFinalPosition(room, guestAlpha, listOf(ownPrior))
        assertTrue("own prior text missing", prompt.contains(ownPrior.text))
        assertTrue("final position cue missing", prompt.contains("最终立场"))
    }

    @Test
    fun `synthesize includes only guest messages`() {
        val guestMsg = message(
            id = "m1",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = "Ship it now.",
        )
        val hostMsg = message(
            id = "m2",
            authorId = COUNCIL_ROOM_HOST_ID,
            authorName = "Host",
            role = "host",
            text = "Thank you.",
        )
        val userMsg = message(
            id = "m3",
            authorId = COUNCIL_ROOM_USER_ID,
            authorName = "You",
            role = "user",
            text = "What do you think?",
        )
        val room = baseRoom(
            mode = CouncilRoomMode.SYNTHESIZE,
            messages = listOf(guestMsg, hostMsg, userMsg),
        )
        val prompt = CouncilRoomPrompts.synthesize(room)
        assertTrue("guest message missing", prompt.contains(guestMsg.text))
        assertFalse("host message should be excluded", prompt.contains(hostMsg.text))
        assertFalse("user message should be excluded", prompt.contains(userMsg.text))
        assertTrue("consensus section missing", prompt.contains("共识"))
        assertTrue("final recommendation missing", prompt.contains("最终建议"))
    }

    @Test
    fun `synthesize excludes streaming or failed guest messages`() {
        val completedGuest = message(
            id = "m1",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = "Completed argument.",
            status = CouncilMessageStatus.COMPLETED,
        )
        val streamingGuest = message(
            id = "m2",
            authorId = "beta",
            authorName = "Beta",
            role = "反对者",
            text = "Streaming partial...",
            status = CouncilMessageStatus.STREAMING,
        )
        val failedGuest = message(
            id = "m3",
            authorId = "beta",
            authorName = "Beta",
            role = "反对者",
            text = "",
            error = "Provider error",
            status = CouncilMessageStatus.FAILED,
        )
        val room = baseRoom(
            mode = CouncilRoomMode.SYNTHESIZE,
            messages = listOf(completedGuest, streamingGuest, failedGuest),
        )
        val prompt = CouncilRoomPrompts.synthesize(room)
        assertTrue("completed guest message missing", prompt.contains(completedGuest.text))
        assertFalse("streaming message should be excluded", prompt.contains(streamingGuest.text))
        assertFalse("failed error should be excluded", prompt.contains(failedGuest.error))
    }

    @Test
    fun `invitedByHostPrompt merges mode opening and host instruction`() {
        val room = baseRoom()
        val instruction = "Focus on security risks."
        val prompt = CouncilRoomPrompts.invitedByHostPrompt(room, guestAlpha, instruction)
        assertTrue("mode cue missing", prompt.contains("Explore"))
        assertTrue("host instruction missing", prompt.contains(instruction))
    }

    @Test
    fun `followUpPrompt includes seed summary and reply metadata`() {
        val seed = message(
            id = "m1",
            authorId = "beta",
            authorName = "Beta",
            role = "反对者",
            text = "Latency will spike under load.",
            mode = CouncilRoomMode.DEBATE,
        )
        val room = baseRoom(mode = CouncilRoomMode.DEBATE, messages = listOf(seed))
        val prompt = CouncilRoomPrompts.followUpPrompt(room, guestAlpha, seed)
        assertTrue("seed author missing", prompt.contains("Beta"))
        assertTrue("seed text missing", prompt.contains(seed.text))
        assertTrue("reply instruction missing", prompt.contains("补充、支持或反驳"))
    }

    @Test
    fun `summaryBlock renders replyTo and continuesFrom refs`() {
        val msg = message(
            id = "m2",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = "I agree with that point.",
            replyToMessageId = "m1",
            continuesFromMessageId = "m0",
        )
        val block = msg.summaryBlock()
        assertTrue("reply ref missing", block.contains("回复 #m1"))
        assertTrue("continues ref missing", block.contains("延续 #m0"))
        assertTrue("author missing", block.contains("Alpha"))
        assertTrue("body missing", block.contains(msg.text))
    }

    @Test
    fun `summaryBlock renders cleanly without refs`() {
        val msg = message(
            id = "m1",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = "Standalone point.",
        )
        val block = msg.summaryBlock()
        assertTrue("body missing", block.contains(msg.text))
        assertFalse("should not contain empty ref placeholders", block.contains("回复"))
        assertFalse("should not contain empty ref placeholders", block.contains("延续"))
    }

    @Test
    fun `summaryBlock respects limit`() {
        val longText = "a".repeat(2_000)
        val msg = message(
            id = "m1",
            authorId = "alpha",
            authorName = "Alpha",
            role = "支持者",
            text = longText,
        )
        val block = msg.summaryBlock(limit = 100)
        assertEquals(100, block.length - block.indexOf("\n") - 1)
    }
}
