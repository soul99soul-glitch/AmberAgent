package app.amber.feature.task

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentTaskStoreTest {
    @Test
    fun latestSnapshotSurvivesStoreRecreationAfterReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = AgentTaskStore(context, Json)
        val taskId = "agent-task-${UUID.randomUUID()}"
        try {
            store.register(
                AgentTaskSnapshot(
                    taskId = taskId,
                    type = "test",
                    title = "Task",
                    status = AgentTaskStatus.RUNNING,
                    createdAtMs = 1L,
                )
            )

            store.update(
                taskId = taskId,
                status = AgentTaskStatus.COMPLETED,
                summary = "finished",
            )

            val reloaded = AgentTaskStore(context, Json).read(taskId)
            assertEquals(AgentTaskStatus.COMPLETED, reloaded?.status)
            assertEquals("finished", reloaded?.summary)
        } finally {
            store.remove(taskId)
        }
    }

    @Test
    fun clearErrorClearsErrorAndCodeWithoutChangingDefaultNullSemantics() = runBlocking {
        val store = AgentTaskStore(ApplicationProvider.getApplicationContext(), Json)
        val taskId = "agent-task-${UUID.randomUUID()}"
        try {
            store.register(
                AgentTaskSnapshot(
                    taskId = taskId,
                    type = "test",
                    title = "Task",
                    status = AgentTaskStatus.FAILED,
                    createdAtMs = 1L,
                    error = "previous failure",
                    lastErrorCode = "previous_failure",
                )
            )

            val preserved = store.update(taskId, status = AgentTaskStatus.RUNNING)
            assertEquals("previous failure", preserved?.error)
            assertEquals("previous_failure", preserved?.lastErrorCode)

            val cleared = store.update(
                taskId,
                status = AgentTaskStatus.COMPLETED,
                clearError = true,
            )
            assertNull(cleared?.error)
            assertNull(cleared?.lastErrorCode)
        } finally {
            store.remove(taskId)
        }
    }
}
