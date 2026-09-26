package app.amber.feature.task

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTaskStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun `register atomically persists and reloads snapshot`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val snapshot = snapshot(
            status = AgentTaskStatus.FAILED,
            queueState = AgentTaskQueueState.TERMINAL,
            recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
            retryPolicy = AgentTaskRetryPolicy(),
        )

        val store = store(root)
        assertEquals(snapshot, store.register(snapshot))

        val persistedFile = snapshotFile(root, snapshot.taskId)
        assertTrue(persistedFile.isFile)
        assertFalse(
            "atomic staging files must not remain after commit",
            persistedFile.parentFile!!.listFiles().orEmpty().any { it.extension == "tmp" },
        )
        assertEquals(snapshot, store(root).read(snapshot.taskId))
    }

    @Test
    fun `reload recovers running task and persists recovered state`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val running = snapshot(
            status = AgentTaskStatus.RUNNING,
            queueState = AgentTaskQueueState.ACTIVE,
            recoveryState = AgentTaskRecoveryState.ACTIVE,
            retryPolicy = AgentTaskRetryPolicy(),
        )
        val taskStore = store(root)
        taskStore.register(running)

        val recoveredStore = store(root)
        val recovered = recoveredStore.read(running.taskId)!!

        assertEquals(AgentTaskStatus.INTERRUPTED, recovered.status)
        assertEquals(AgentTaskQueueState.TERMINAL, recovered.queueState)
        assertEquals("interrupted_by_restart", recovered.lastErrorCode)
        assertEquals(recovered, readSnapshot(root, running.taskId))

        val updated = recoveredStore.update(running.taskId, summary = "recovered and updated")!!
        assertEquals("recovered and updated", recoveredStore.read(running.taskId)?.summary)
        val reloaded = store(root).read(running.taskId)!!
        assertEquals(updated.summary, reloaded.summary)
        assertEquals(updated.status, reloaded.status)
    }

    @Test
    fun `reads and writes wait for asynchronous startup recovery`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val running = snapshot(
            status = AgentTaskStatus.RUNNING,
            queueState = AgentTaskQueueState.ACTIVE,
            recoveryState = AgentTaskRecoveryState.ACTIVE,
            retryPolicy = AgentTaskRetryPolicy(),
        )
        store(root).register(running)

        val readDispatcher = QueuedDispatcher()
        val readStore = AgentTaskStore(TestContext(root), json, readDispatcher)
        val startupSnapshots = async { readStore.awaitReady() }
        val pendingRead = async { readStore.read(running.taskId) }
        val pendingList = async { readStore.list() }
        yield()

        assertTrue(readStore.tasksFlow.value.isEmpty())
        assertFalse(startupSnapshots.isCompleted)
        assertFalse(pendingRead.isCompleted)
        assertFalse(pendingList.isCompleted)

        readDispatcher.runAll()

        val baseline = startupSnapshots.await().single()
        assertEquals(AgentTaskStatus.INTERRUPTED, baseline.status)
        assertEquals(baseline, pendingRead.await())
        assertEquals(listOf(baseline), pendingList.await())
        assertEquals(listOf(baseline), readStore.tasksFlow.value)

        readStore.update(running.taskId, summary = "after startup baseline")
        assertEquals(listOf(baseline), readStore.awaitReady())

        val writeDispatcher = QueuedDispatcher()
        val writeStore = AgentTaskStore(TestContext(root), json, writeDispatcher)
        val pendingWrite = async {
            writeStore.update(running.taskId, summary = "written after recovery")
        }
        assertFalse(pendingWrite.isCompleted)

        writeDispatcher.runAll()

        val updated = pendingWrite.await()!!
        val reloaded = store(root).read(running.taskId)!!
        assertEquals("written after recovery", updated.summary)
        assertEquals(updated.summary, reloaded.summary)
        assertEquals(AgentTaskStatus.INTERRUPTED, reloaded.status)
    }

    @Test
    fun `write failure leaves memory and flow at previous snapshot`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val original = snapshot(
            status = AgentTaskStatus.FAILED,
            queueState = AgentTaskQueueState.TERMINAL,
            recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
            retryPolicy = AgentTaskRetryPolicy(),
        )
        val store = store(root)
        store.register(original)

        val taskDir = snapshotFile(root, original.taskId).parentFile!!
        assertTrue(snapshotFile(root, original.taskId).delete())
        assertTrue(taskDir.delete())
        assertTrue(taskDir.createNewFile())

        try {
            store.update(taskId = original.taskId, summary = "must not publish")
            fail("persist failure must be visible to the caller")
        } catch (_: IOException) {
            // Expected: the snapshot directory was replaced with a regular file.
        }

        assertEquals(original, store.read(original.taskId))
        assertEquals(listOf(original), store.tasksFlow.value)
    }

    @Test
    fun `successful retry clears old error and code in memory and on disk`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val failed = snapshot(
            status = AgentTaskStatus.FAILED,
            queueState = AgentTaskQueueState.TERMINAL,
            recoveryState = AgentTaskRecoveryState.RETRYABLE,
            retryPolicy = AgentTaskRetryPolicy(retryable = true, maxRetries = 2),
        )
        val store = store(root)
        store.register(failed, retry = { true })

        val retried = store.retry(failed.taskId)

        assertEquals(AgentTaskStatus.QUEUED, retried.status)
        assertEquals(AgentTaskRecoveryState.ACTIVE, retried.recoveryState)
        assertNull(retried.error)
        assertNull(retried.lastErrorCode)

        val persisted = readSnapshot(root, failed.taskId)
        assertEquals(AgentTaskStatus.QUEUED, persisted.status)
        assertNull(persisted.error)
        assertNull(persisted.lastErrorCode)
    }

    @Test
    fun `corrupt snapshot remains untouched and does not hide valid tasks`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val valid = snapshot(AgentTaskStatus.FAILED, AgentTaskQueueState.TERMINAL,
            AgentTaskRecoveryState.CLEANUP_ONLY, AgentTaskRetryPolicy())
        store(root).register(valid)
        val corrupt = snapshotFile(root, "incomplete")
        corrupt.writeText("{\"taskId\":")

        assertEquals(listOf(valid), store(root).list())
        assertEquals("{\"taskId\":", corrupt.readText())
    }

    @Test
    fun `failed register does not expose a task that was never saved`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val store = store(root)
        store.awaitReady()
        val taskDir = File(root, "amberagent/tasks")
        assertTrue(taskDir.delete())
        assertTrue(taskDir.createNewFile())
        try {
            store.register(snapshot(AgentTaskStatus.RUNNING, AgentTaskQueueState.ACTIVE,
                AgentTaskRecoveryState.ACTIVE, AgentTaskRetryPolicy()))
            fail("register must report a failed durable write")
        } catch (_: IOException) {
            assertTrue(store.list().isEmpty())
            assertTrue(store.tasksFlow.value.isEmpty())
        }
    }

    @Test
    fun `scheduler completion clears previous error and code`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val failed = snapshot(
            status = AgentTaskStatus.FAILED,
            queueState = AgentTaskQueueState.TERMINAL,
            recoveryState = AgentTaskRecoveryState.CLEANUP_ONLY,
            retryPolicy = AgentTaskRetryPolicy(),
        )
        val store = store(root)
        store.register(failed)

        val completed = AgentTaskScheduler(store).complete(failed.taskId, summary = "done")!!

        assertEquals(AgentTaskStatus.COMPLETED, completed.status)
        assertNull(completed.error)
        assertNull(completed.lastErrorCode)
        val persisted = readSnapshot(root, failed.taskId)
        assertNull(persisted.error)
        assertNull(persisted.lastErrorCode)
    }

    @Test
    fun `scheduler terminal update ignores stale task spec`() = runBlocking {
        val root = tempFolder.newFolder("files")
        val oldSpec = buildJsonObject { put("run_id", "old-run") }
        val currentSpec = buildJsonObject { put("run_id", "current-run") }
        val running = snapshot(
            status = AgentTaskStatus.RUNNING,
            queueState = AgentTaskQueueState.ACTIVE,
            recoveryState = AgentTaskRecoveryState.ACTIVE,
            retryPolicy = AgentTaskRetryPolicy(),
        ).copy(spec = currentSpec)
        val store = store(root)
        store.register(running)
        val scheduler = AgentTaskScheduler(store)

        assertNull(scheduler.complete(running.taskId, expectedSpec = oldSpec))
        assertNull(scheduler.fail(running.taskId, message = "stale", expectedSpec = oldSpec))
        assertEquals(AgentTaskStatus.RUNNING, store.read(running.taskId)?.status)

        val failed = scheduler.fail(
            running.taskId,
            message = "current failure",
            expectedSpec = currentSpec,
        )!!
        assertEquals(AgentTaskStatus.FAILED, failed.status)

        store.upsert(running)
        val completed = scheduler.complete(running.taskId, expectedSpec = currentSpec)!!
        assertEquals(AgentTaskStatus.COMPLETED, completed.status)
    }

    private fun store(root: File): AgentTaskStore = AgentTaskStore(TestContext(root), json)

    private fun snapshotFile(root: File, taskId: String): File =
        File(root, "amberagent/tasks/$taskId.json")

    private fun readSnapshot(root: File, taskId: String): AgentTaskSnapshot =
        json.decodeFromString(
            AgentTaskSnapshot.serializer(),
            snapshotFile(root, taskId).readText(),
        )

    private fun snapshot(
        status: AgentTaskStatus,
        queueState: AgentTaskQueueState,
        recoveryState: AgentTaskRecoveryState,
        retryPolicy: AgentTaskRetryPolicy,
    ) = AgentTaskSnapshot(
        taskId = "task-1",
        type = "terminal",
        title = "Task",
        status = status,
        queueState = queueState,
        recoveryState = recoveryState,
        retryPolicy = retryPolicy,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        error = "stale error",
        lastErrorCode = "stale_code",
    )

    private class TestContext(filesDir: File) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = filesDir

        override fun getFilesDir(): File = root
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runAll() {
            while (true) {
                val next = if (queued.isEmpty()) null else queued.removeFirst()
                next?.run() ?: return
            }
        }
    }
}
