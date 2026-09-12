package app.amber.feature.cron

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.amber.feature.task.AgentTaskStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentCronManagerConcurrencyTest {
    private lateinit var context: Context
    private lateinit var manager: AgentCronManager
    private lateinit var workManager: WorkManager
    private var taskId: String? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val workerExecutor = Executor { /* Keep the manual request ENQUEUED for this interleaving. */ }
        val taskExecutor = Executor { runnable ->
            Thread(runnable, "CronWorkManagerTest").apply { isDaemon = true }.start()
        }
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(
                context,
                Configuration.Builder()
                    .setExecutor(workerExecutor)
                    .setTaskExecutor(taskExecutor)
                    .build(),
            )
        }
        workManager = WorkManager.getInstance(context)
        manager = AgentCronManager(context, Json, AgentTaskStore(context, Json))
    }

    @After
    fun tearDown() {
        runBlocking {
            taskId?.let { manager.deleteTask(it) }
        }
    }

    @Test
    fun `cancelled worker finally cannot replace the manual request`() {
        runBlocking {
            val task = manager.createTask(
                title = "Concurrency test",
                prompt = "Test prompt",
                cronExpression = "*/5 * * * *",
                timezoneId = "UTC",
                enabled = true,
            )
            taskId = task.id
            val oldRequest = activeWork(task.id)

            assertTrue(manager.runTaskNow(task.id))
            val manualRequest = activeWork(task.id)
            assertNotEquals(oldRequest.id, manualRequest.id)
            assertTrue(
                workManager.getWorkInfosForUniqueWork("amberagent_cron_${task.id}").get()
                    .none { it.id == oldRequest.id && it.state in ACTIVE_STATES },
            )

            val beforeOldWorkerCallback = manager.listTasks().single()
            assertEquals(null, manager.prepareTriggeredRun(task.id, oldRequest.id))
            assertEquals(beforeOldWorkerCallback, manager.listTasks().single())

            // This is the old worker's NonCancellable finally block after the manual
            // REPLACE has committed. It must not enqueue another REPLACE.
            manager.scheduleNextRun(task.id, oldRequest.id)

            assertEquals(manualRequest.id, activeWork(task.id).id)
        }
    }

    @Test
    fun `startup reschedule keeps a manual run for a disabled task`() {
        runBlocking {
            val task = manager.createTask(
                title = "Disabled concurrency test",
                prompt = "Test prompt",
                cronExpression = "*/5 * * * *",
                timezoneId = "UTC",
                enabled = false,
            )
            taskId = task.id

            assertTrue(manager.runTaskNow(task.id))
            val manualRequest = activeWork(task.id)

            manager.rescheduleAll()

            assertEquals(manualRequest.id, activeWork(task.id).id)
        }
    }

    private fun activeWork(taskId: String): WorkInfo {
        return workManager.getWorkInfosForUniqueWork("amberagent_cron_$taskId").get()
            .single { it.state in ACTIVE_STATES }
    }

    private companion object {
        val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )
    }
}
