package app.amber.feature.modelcouncil

import app.amber.core.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Round-trip serialization tests for the Council Room data model.
 *
 * These guard the persistence contract: any change to [CouncilRoom] /
 * [CouncilParticipant] / [CouncilMessage] / [CouncilPhaseMarker] that breaks
 * deserialization of previously-stored JSON would silently corrupt the
 * `council_state` column on upgrade.
 *
 * IMPORTANT: we use the REAL [JsonInstant] instance (the same one
 * [app.amber.core.repository.CouncilRoomRepository] uses to write to disk) so
 * the test's round-trip actually exercises the on-disk wire format — not a
 * divergent test-only Json config. A test-only config that silently differs
 * (e.g. explicitNulls) would let the test pass while disk reads break.
 */
class CouncilRoomSerializationTest {

    private val json = JsonInstant

    @Test
    fun councilRoomRoundTripsWithDefaults() {
        val now = 1_700_000_000_000L
        val original = CouncilRoom(
            id = "room-1",
            conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            hostAssistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            objective = "要不要做成独立页面？",
            createdAtMs = now,
        )
        val encoded = json.encodeToString(CouncilRoom.serializer(), original)
        val decoded = json.decodeFromString(CouncilRoom.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun councilRoomRoundTripsWithFullGraph() {
        val now = 1_700_000_000_000L
        val host = CouncilParticipant(
            id = COUNCIL_ROOM_HOST_ID,
            name = "Amber",
            role = "host",
            kind = CouncilParticipantKind.HOST,
        )
        val guest = CouncilParticipant(
            id = "guest-deepseek",
            name = "DeepSeek",
            role = "supporter",
            kind = CouncilParticipantKind.GUEST,
            modelId = Uuid.parse("33333333-3333-3333-3333-333333333333"),
            status = CouncilParticipantStatus.SPOKEN,
            systemPrompt = "你是支持者。",
            providerName = "DeepSeek",
            modelName = "deepseek-chat",
        )
        val userMsg = CouncilMessage(
            id = "cm-1",
            authorId = COUNCIL_ROOM_USER_ID,
            authorName = "You",
            role = "user",
            round = 0,
            mode = CouncilRoomMode.EXPLORE,
            text = "讨论一下",
            createdAtMs = now,
        )
        val guestReply = CouncilMessage(
            id = "cm-2",
            authorId = "guest-deepseek",
            authorName = "DeepSeek",
            role = "supporter",
            round = 1,
            mode = CouncilRoomMode.DEBATE,
            text = "我认为应该独立。",
            createdAtMs = now + 1_000,
            replyToMessageId = "cm-1",
            continuesFromMessageId = null,
            invitedBy = COUNCIL_ROOM_HOST_ID,
        )
        val original = CouncilRoom(
            id = "room-1",
            conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            hostAssistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            objective = "要不要做成独立页面？",
            context = "产品决策",
            mode = CouncilRoomMode.DEBATE,
            status = CouncilRoomStatus.DEBATING,
            participants = listOf(host, guest),
            messages = listOf(userMsg, guestReply),
            phaseMarkers = listOf(
                CouncilPhaseMarker("pm-1", "Explore · Opening", CouncilRoomMode.EXPLORE, now),
                CouncilPhaseMarker("pm-2", "切换到 Debate", CouncilRoomMode.DEBATE, now + 500),
            ),
            synthesis = "",
            round = 1,
            maxRounds = 5,
            createdAtMs = now,
            updatedAtMs = now + 2_000,
        )

        val encoded = json.encodeToString(CouncilRoom.serializer(), original)
        val decoded = json.decodeFromString(CouncilRoom.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals(2, decoded.participants.size)
        assertEquals(2, decoded.messages.size)
        assertEquals("cm-1", decoded.messages[1].replyToMessageId)
        assertEquals(COUNCIL_ROOM_HOST_ID, decoded.messages[1].invitedBy)
        assertEquals(CouncilRoomMode.DEBATE, decoded.messages[1].mode)
    }

    @Test
    fun councilRoomSurvivesForwardCompatibleExtraFields() {
        // Simulate a future schema adding an unknown field. Old code must not crash.
        val futureJson = """
            {
              "id": "room-1",
              "conversation_id": "11111111-1111-1111-1111-111111111111",
              "host_assistant_id": "22222222-2222-2222-2222-222222222222",
              "objective": "x",
              "created_at_ms": 1700000000000,
              "future_field": "ignored"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(CouncilRoom.serializer(), futureJson)
        assertEquals("room-1", decoded.id)
    }

    @Test
    fun corruptJsonReturnsNullViaRunCatchingContract() {
        // CouncilRoomRepository.decodeRoom uses runCatching{}.getOrNull() to
        // gracefully degrade on corrupt persisted state. Verify the contract:
        // malformed JSON must NOT throw out of decodeFromString (it should be
        // caught by the caller), and the runCatching wrapper yields null.
        val corrupt = "{ this is not valid json"
        val decoded = runCatching { json.decodeFromString(CouncilRoom.serializer(), corrupt) }.getOrNull()
        assertNull("Corrupt JSON must yield null via runCatching.getOrNull()", decoded)
    }

    @Test
    fun enumSerialNamesAreStableWireFormat() {
        // The wire format is the contract with on-disk SQLite data. Lock it down.
        assertEquals("\"explore\"", json.encodeToString(CouncilRoomMode.serializer(), CouncilRoomMode.EXPLORE))
        assertEquals("\"debate\"", json.encodeToString(CouncilRoomMode.serializer(), CouncilRoomMode.DEBATE))
        assertEquals("\"synthesize\"", json.encodeToString(CouncilRoomMode.serializer(), CouncilRoomMode.SYNTHESIZE))

        assertEquals("\"exploring\"", json.encodeToString(CouncilRoomStatus.serializer(), CouncilRoomStatus.EXPLORING))
        assertEquals("\"finalizing\"", json.encodeToString(CouncilRoomStatus.serializer(), CouncilRoomStatus.FINALIZING))
        assertEquals("\"finalized\"", json.encodeToString(CouncilRoomStatus.serializer(), CouncilRoomStatus.FINALIZED))

        assertEquals("\"host\"", json.encodeToString(CouncilParticipantKind.serializer(), CouncilParticipantKind.HOST))
        assertEquals("\"guest\"", json.encodeToString(CouncilParticipantKind.serializer(), CouncilParticipantKind.GUEST))

        assertEquals("\"speaking\"", json.encodeToString(CouncilParticipantStatus.serializer(), CouncilParticipantStatus.SPEAKING))
        assertEquals("\"dismissed\"", json.encodeToString(CouncilParticipantStatus.serializer(), CouncilParticipantStatus.DISMISSED))
    }

    @Test
    fun statusRunningAndTerminalExtensionsAreCorrect() {
        assertTrue(CouncilRoomStatus.EXPLORING.running)
        assertTrue(CouncilRoomStatus.DEBATING.running)
        assertTrue(CouncilRoomStatus.FINALIZING.running)
        assertTrue(!CouncilRoomStatus.IDLE.running)
        assertTrue(!CouncilRoomStatus.FINALIZED.running)

        assertTrue(CouncilRoomStatus.FINALIZED.terminal)
        assertTrue(CouncilRoomStatus.CANCELLED.terminal)
        assertTrue(CouncilRoomStatus.FAILED.terminal)
        assertTrue(!CouncilRoomStatus.EXPLORING.terminal)
    }

    @Test
    fun messageStatusRunningExtensionIsCorrect() {
        assertTrue(CouncilMessageStatus.PENDING.running)
        assertTrue(CouncilMessageStatus.STREAMING.running)
        assertTrue(!CouncilMessageStatus.COMPLETED.running)
    }

    @Test
    fun hostAndActiveGuestsDerivedPropertiesWork() {
        val now = 1_700_000_000_000L
        val host = CouncilParticipant(COUNCIL_ROOM_HOST_ID, "Amber", "host", CouncilParticipantKind.HOST)
        val active = CouncilParticipant("g1", "DeepSeek", "supporter", CouncilParticipantKind.GUEST, status = CouncilParticipantStatus.IDLE)
        val speaking = CouncilParticipant("g2", "GLM", "opponent", CouncilParticipantKind.GUEST, status = CouncilParticipantStatus.SPEAKING)
        val dismissed = CouncilParticipant("g3", "Risk", "risk", CouncilParticipantKind.GUEST, status = CouncilParticipantStatus.DISMISSED)
        val room = CouncilRoom(
            id = "r",
            conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            hostAssistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            objective = "x",
            participants = listOf(host, active, speaking, dismissed),
            createdAtMs = now,
        )
        assertNotNull(room.host)
        assertEquals(COUNCIL_ROOM_HOST_ID, room.host?.id)
        assertEquals(2, room.activeGuests.size)
        assertEquals("g1", room.activeGuests[0].id)
        assertEquals("g2", room.activeGuests[1].id)
        assertEquals("DeepSeek", room.participantById("g1")?.name)
        assertNull(room.participantById("nonexistent"))
    }
}
