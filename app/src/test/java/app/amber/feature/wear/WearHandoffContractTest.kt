package app.amber.feature.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * E01 Phase 8: handoff idempotency contract. The watch retries with the same
 * requestId; the phone's intake must never double-execute and must answer
 * duplicates from the durable record.
 */
class WearHandoffContractTest {

    private fun envelope(
        requestId: String = Uuid.random().toString(),
        operation: WearOperation = WearOperation.ASK,
        createdAt: Long = 1_000L,
        payload: String = "帮我总结今天的日程",
    ) = WearTaskEnvelope(requestId, operation, createdAt, payload)

    @Test
    fun `new envelope is accepted`() {
        val result = WearHandoffPolicy.intake(envelope(), existing = null, nowEpochMs = 2_000)
        assertTrue(result is WearHandoffPolicy.Intake.Accept)
    }

    @Test
    fun `duplicate requestId returns the persisted record regardless of status`() {
        val record = WearHandoffRecord(
            requestId = "req-1",
            operation = WearOperation.ASK,
            status = WearHandoffStatus.COMPLETE,
            receivedAtEpochMs = 1_000,
            resultReference = "task-42",
        )
        val duplicate = WearHandoffPolicy.intake(envelope("req-1"), existing = record, nowEpochMs = 2_000)
        assertEquals(WearHandoffPolicy.Intake.Duplicate(record), duplicate)
        // A retry of a still-pending request is also a duplicate, not a re-dispatch.
        val pending = record.copy(status = WearHandoffStatus.PENDING, resultReference = null)
        assertTrue(
            WearHandoffPolicy.intake(envelope("req-1"), existing = pending, nowEpochMs = 2_000)
                is WearHandoffPolicy.Intake.Duplicate,
        )
    }

    @Test
    fun `null envelopes are rejected and blank ids cannot even be constructed`() {
        assertTrue(WearHandoffPolicy.intake(null, null, 1_000) is WearHandoffPolicy.Intake.Reject)
        // The data-class boundary itself refuses blank ids, so a blank id can
        // never reach the policy layer; the policy check is defense in depth.
        assertTrue(runCatching { envelope(" ") }.isFailure)
    }

    @Test
    fun `future-dated envelope beyond clock skew is rejected`() {
        val future = WearHandoffPolicy.intake(
            envelope(createdAt = 10 * 60_000L),
            existing = null,
            nowEpochMs = 1_000,
        )
        assertTrue(future is WearHandoffPolicy.Intake.Reject)
        // Within the 5-minute skew window it is accepted.
        assertTrue(
            WearHandoffPolicy.intake(envelope(createdAt = 4 * 60_000L), null, 1_000)
                is WearHandoffPolicy.Intake.Accept,
        )
    }

    @Test
    fun `payload over the bound is rejected by construction`() {
        assertTrue(
            runCatching { envelope(payload = "x".repeat(WearTaskEnvelope.MAX_PAYLOAD_CHARS + 1)) }.isFailure,
        )
        assertTrue(
            runCatching { envelope(payload = "x".repeat(WearTaskEnvelope.MAX_PAYLOAD_CHARS)) }.isSuccess,
        )
    }

    @Test
    fun `terminal statuses are complete and failed only`() {
        fun recordOf(status: WearHandoffStatus) =
            WearHandoffRecord("r", WearOperation.ASK, status, 1_000)

        assertTrue(WearHandoffPolicy.isTerminal(recordOf(WearHandoffStatus.COMPLETE)))
        assertTrue(WearHandoffPolicy.isTerminal(recordOf(WearHandoffStatus.FAILED)))
        assertTrue(!WearHandoffPolicy.isTerminal(recordOf(WearHandoffStatus.ACCEPTED)))
        assertTrue(!WearHandoffPolicy.isTerminal(recordOf(WearHandoffStatus.PENDING)))
    }
}
