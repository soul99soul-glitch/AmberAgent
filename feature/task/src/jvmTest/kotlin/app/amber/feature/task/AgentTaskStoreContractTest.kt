package app.amber.feature.task

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTaskStoreContractTest {

    @Test
    fun nullablePatchCanExplicitlyClearPersistedValues() = withStore { store ->
        runBlocking {
            store.register(snapshot("clear").copy(error = "old", lastErrorCode = "old_code"))
            store.update("clear", clearError = true, clearLastErrorCode = true)
        }

        assertNull(store.read("clear")?.error)
        assertNull(store.read("clear")?.lastErrorCode)
    }

    @Test
    fun retryableTaskRequiresLiveAdapter() = withStore { store ->
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                store.register(
                    snapshot("retry").copy(
                        retryPolicy = AgentTaskRetryPolicy(retryable = true, maxRetries = 1),
                    ),
                )
            }
        }
        assertNull(store.read("retry"))
    }

    @Test
    fun updateCannotMakePersistedMetadataRetryableWithoutAdapter() = withStore { store ->
        runBlocking { store.register(snapshot("retry-update")) }

        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                store.update(
                    "retry-update",
                    retryPolicy = AgentTaskRetryPolicy(retryable = true, maxRetries = 1),
                )
            }
        }
        assertEquals(false, store.read("retry-update")?.retryPolicy?.retryable)
    }

    @Test
    fun concurrentMutationAndSnapshotReadsStayConsistent() = withStore { store ->
        runBlocking {
            coroutineScope {
                repeat(100) { index ->
                    launch(Dispatchers.Default) { store.register(snapshot("task-$index")) }
                    launch(Dispatchers.Default) { store.list().forEach { assertTrue(it.taskId.isNotBlank()) } }
                }
            }
        }

        assertEquals(100, store.list().size)
        assertEquals(store.list(), store.tasksFlow.value)
    }

    private fun snapshot(id: String) = AgentTaskSnapshot(
        taskId = id,
        type = "test",
        title = id,
        status = AgentTaskStatus.RUNNING,
        createdAtMs = 1L,
    )

    private fun withStore(block: (AgentTaskStore) -> Unit) {
        val root = Files.createTempDirectory("agent-task-store-test").toFile()
        try {
            block(AgentTaskStore(root.absolutePath, Json { ignoreUnknownKeys = true }))
        } finally {
            root.deleteRecursively()
        }
    }
}
