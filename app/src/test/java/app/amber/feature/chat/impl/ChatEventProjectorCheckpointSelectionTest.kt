package app.amber.feature.chat.impl

import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.feature.chat.api.ChatEventPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEventProjectorCheckpointSelectionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun record(seq: Long, type: String, payload: String) = AgentEventRecord(
        eventId = "run_$seq",
        runId = "run",
        parentRunId = null,
        seq = seq,
        type = type,
        payloadType = "x",
        payload = payload,
        payloadSchemaVersion = 1,
        agentDescriptorId = "chat_turn",
        agentVersion = "1.0.0",
        isFinal = true,
        ts = seq,
    )

    private fun checkpointPayload(messageId: String): String = json.encodeToString(
        ChatEventPayload.StreamCheckpoint.serializer(),
        ChatEventPayload.StreamCheckpoint(
            conversationId = "c1",
            messageId = messageId,
            partsHash = "h",
        ),
    )

    @Test
    fun `picks the checkpoint with the highest seq`() {
        val events = listOf(
            record(1, "UserMessageAccepted", "{}"),
            record(2, ChatEventPayload.StreamCheckpoint.TYPE, checkpointPayload("m-old")),
            record(3, ChatEventPayload.StreamCheckpoint.TYPE, checkpointPayload("m-new")),
        )

        val latest = ChatEventProjector.latestStreamCheckpoint(events, json)

        assertEquals("m-new", latest?.messageId)
    }

    @Test
    fun `returns null when run has no checkpoints`() {
        val events = listOf(record(1, "UserMessageAccepted", "{}"))
        assertNull(ChatEventProjector.latestStreamCheckpoint(events, json))
    }

    @Test
    fun `corrupt checkpoint payload degrades to null instead of throwing`() {
        val events = listOf(
            record(1, ChatEventPayload.StreamCheckpoint.TYPE, "not-json"),
        )
        assertNull(ChatEventProjector.latestStreamCheckpoint(events, json))
    }
}
