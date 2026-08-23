package app.amber.feature.runtime

import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.ResponseCursor
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.openai.StoredResponseApi
import app.amber.ai.provider.providers.openai.StoredResponseCancelResult
import app.amber.ai.provider.providers.openai.StoredResponseState
import app.amber.ai.provider.providers.openai.StoredResponseStatus
import app.amber.ai.ui.MessageChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * P6-01 Stop path — server-side cancel is awaited for a decidable outcome
 * (plan §P6-01 #5): an unconfirmed cancel must NOT pretend the run was
 * cancelled; the caller keeps WAITING_EXTERNAL with the cursor for recovery.
 */
class StoredResponseStopCancelTest {

    private open class FakeApi(var cancelResult: StoredResponseCancelResult) : StoredResponseApi {
        var cancelledResponseId: String? = null
        override suspend fun fetchStatus(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): StoredResponseStatus = StoredResponseStatus(StoredResponseState.IN_PROGRESS, responseId)

        override suspend fun streamStored(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
            cursor: ResponseCursor?,
            store: ResponseResumeStore,
            runId: String,
        ): Flow<MessageChunk> = flowOf()

        override suspend fun cancel(
            providerSetting: ProviderSetting.OpenAI,
            responseId: String,
        ): StoredResponseCancelResult {
            cancelledResponseId = responseId
            return cancelResult
        }
    }

    private class FakeGateway(var session: StoredResponseGateway.StoredResponseSession?) : StoredResponseGateway {
        override suspend fun resolve(runId: String): StoredResponseGateway.StoredResponseSession? = session
    }

    private class InMemoryStore : ResponseResumeStore {
        var row: Triple<String, String, Long>? = null // runId, responseId, sequence
        override suspend fun save(runId: String, responseId: String, sequence: Long, providerId: String) {
            row = Triple(runId, responseId, sequence)
        }

        override suspend fun load(runId: String): ResponseCursor? =
            row?.takeIf { it.first == runId }?.let { ResponseCursor(it.second, it.third, "provider_1") }

        override suspend fun clear(runId: String) {
            if (row?.first == runId) row = null
        }
    }

    private fun session(api: StoredResponseApi) = StoredResponseGateway.StoredResponseSession(
        cursor = ResponseCursor("resp_1", 5, "provider_1"),
        providerSetting = ProviderSetting.OpenAI(id = Uuid.random(), baseUrl = "https://api.openai.com/v1", useResponseApi = true),
        api = api,
    )

    @Test
    fun nothingStoredIsDecidableWithoutServerCall() = runBlocking {
        val store = InMemoryStore()
        val stopCancel = StoredResponseStopCancel(FakeGateway(null), store)

        assertTrue(stopCancel.cancelStored("run_1"))
    }

    @Test
    fun confirmedServerCancelIsDecidableAndClearsCursor() = runBlocking {
        val store = InMemoryStore()
        store.save("run_1", "resp_1", 5, "provider_1")
        val api = FakeApi(StoredResponseCancelResult.CancelledDecided)
        val stopCancel = StoredResponseStopCancel(FakeGateway(session(api)), store)

        assertTrue(stopCancel.cancelStored("run_1"))
        assertEquals("resp_1", api.cancelledResponseId)
        assertNull(store.load("run_1"))
    }

    @Test
    fun cancelRaceServerAlreadySettledIsStillDecidable() = runBlocking {
        // The server may have completed the response before the cancel lands —
        // a 2xx from the cancel endpoint is a decidable outcome either way.
        val store = InMemoryStore()
        store.save("run_1", "resp_1", 5, "provider_1")
        val api = FakeApi(StoredResponseCancelResult.CancelledDecided)
        val stopCancel = StoredResponseStopCancel(FakeGateway(session(api)), store)

        assertTrue(stopCancel.cancelStored("run_1"))
        assertNull(store.load("run_1"))
    }

    @Test
    fun failedServerCancelIsUndecidableAndKeepsCursor() = runBlocking {
        val store = InMemoryStore()
        store.save("run_1", "resp_1", 5, "provider_1")
        val api = FakeApi(StoredResponseCancelResult.CancelFailed)
        val stopCancel = StoredResponseStopCancel(FakeGateway(session(api)), store)

        // Undecidable — the caller must publish WAITING_EXTERNAL, not CANCELLED.
        assertEquals(false, stopCancel.cancelStored("run_1"))
        assertNotNull("cursor must survive for recovery", store.load("run_1"))
        assertEquals(5L, store.load("run_1")!!.sequence)
    }

    @Test
    fun cancelApiThrowingIsUndecidableForCaller() = runBlocking {
        // The production ResponseAPI maps transport errors to CancelFailed;
        // a throwing implementation escapes and the Stop path's runCatching
        // treats it as unconfirmed (WAITING_EXTERNAL) — never pretended CANCELLED.
        val store = InMemoryStore()
        store.save("run_1", "resp_1", 5, "provider_1")
        val api = object : FakeApi(StoredResponseCancelResult.CancelledDecided) {
            override suspend fun cancel(
                providerSetting: ProviderSetting.OpenAI,
                responseId: String,
            ): StoredResponseCancelResult = throw IOException("offline")
        }
        val stopCancel = StoredResponseStopCancel(FakeGateway(session(api)), store)

        try {
            stopCancel.cancelStored("run_1")
            org.junit.Assert.fail("expected the cancel failure to escape")
        } catch (expected: IOException) {
            // the caller's runCatching converts this into WAITING_EXTERNAL
        }
        assertNotNull(store.load("run_1"))
    }
}
