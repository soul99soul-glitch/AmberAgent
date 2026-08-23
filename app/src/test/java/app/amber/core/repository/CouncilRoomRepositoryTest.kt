package app.amber.core.repository

import app.amber.agent.data.db.dao.ConversationDAO
import app.amber.feature.modelcouncil.CouncilRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.uuid.Uuid

class CouncilRoomRepositoryTest {
    @Test
    fun `debounced upsert persists without an explicit flush`() = runBlocking {
        var savedState: String? = null
        val dao = Proxy.newProxyInstance(
            ConversationDAO::class.java.classLoader,
            arrayOf(ConversationDAO::class.java),
        ) { _, method, args ->
            when (method.name) {
                "updateCouncilState" -> {
                    val continuation = args?.last() as Continuation<*>
                    if (continuation.context[Job]?.isActive == false) {
                        throw CancellationException("Room DAO observed caller cancellation")
                    }
                    savedState = args[1] as String?
                    Unit
                }
                "toString" -> "FakeConversationDAO"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ConversationDAO
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = CouncilRoomRepository(dao, scope)
        val room = CouncilRoom(
            id = "room-1",
            conversationId = Uuid.parse("11111111-1111-1111-1111-111111111111"),
            hostAssistantId = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            objective = "Persist me",
            createdAtMs = 1L,
        )

        try {
            repository.upsertRoom(room)
            withTimeout(2_000L) {
                while (savedState == null) delay(20L)
            }
            assertNotNull(savedState)
        } finally {
            scope.cancel()
        }
    }
}
